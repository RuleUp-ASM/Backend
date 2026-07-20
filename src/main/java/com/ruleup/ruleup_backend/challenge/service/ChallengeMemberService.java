package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.JoinResponse;
import com.ruleup.ruleup_backend.challenge.dto.LeaveResponse;
import com.ruleup.ruleup_backend.challenge.dto.MemberListResponse;
import com.ruleup.ruleup_backend.challenge.dto.RoleChangeResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.SetupStatus;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 챌린지 멤버십 (생성 및 라이프사이클 스펙 §5·§6·§7): 가입 / 탈퇴 / 역할 변경 / 멤버 목록.
 *  - 가입(§5): 승인 절차 없이 검증 통과 시 즉시 ACTIVE. 판정 순서 종료→재참여금지→정원(락)→기준온도→모더레이션.
 *  - 목록(§7): 현재 멤버(ACTIVE)만. 익명 챌린지는 닉네임 마스킹.
 */
@Service
@RequiredArgsConstructor
public class ChallengeMemberService {

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final ReputationScoreRepository reputationScoreRepository;
    private final VerificationDailyRepository verificationDailyRepository;

    // ===== §5 가입 =====
    /**
     * 챌린지 가입. 승인 절차 없이 검증 통과 시 즉시 ACTIVE.
     * 판정 순서(스펙 §5): ① 종료 → ② 재참여 이력(uq_member) → ③ 정원(행 잠금으로 동시 가입 경합 차단)
     *                    → ④ 기준 온도 → ⑤ 이미지 모더레이션 통과 여부.
     */
    @Transactional
    public JoinResponse join(UUID userId, UUID challengeId) {
        // 정원 경합을 직렬화하기 위해 챌린지 행을 잠근 채로 판정한다("마지막 1자리 동시 가입" 차단).
        Challenge c = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        // ① 종료된 챌린지는 가입 개념 없음
        if (c.getStatus() == ChallengeStatus.COMPLETED)
            throw new BusinessException(ErrorCode.CHALLENGE_COMPLETED);

        // ② 재참여 금지(uq_member): 멤버십 행이 있으면 ACTIVE=이미 참여, 그 외(LEFT/REMOVED)=재참여 영구 불가
        ChallengeMember existing = memberRepository.findByChallengeIdAndUserId(challengeId, userId).orElse(null);
        if (existing != null) {
            if (existing.isActive()) throw new BusinessException(ErrorCode.ALREADY_JOINED);
            throw new BusinessException(ErrorCode.REJOIN_FORBIDDEN);
        }

        // ③ 정원(잠금 하에서 현재 ACTIVE 수 확인)
        Integer cap = c.getMaxParticipants();
        long activeCount = memberRepository.countByChallengeIdAndStatus(challengeId, MemberStatus.ACTIVE);
        if (cap != null && activeCount >= cap)
            throw new BusinessException(ErrorCode.CHALLENGE_FULL);

        // ④ 기준 온도(GROUP + 기준 설정 시)
        if (c.isGroup() && c.getMinMannerTemperature() != null
                && mannerTemp(userId).compareTo(c.getMinMannerTemperature()) < 0)
            throw new BusinessException(ErrorCode.MANNER_TEMPERATURE_BELOW_MINIMUM);

        // ⑤ 이미지 모더레이션 미통과(PENDING_REVIEW/REJECTED)면 모집 차단
        if (!c.isModerationCleared())
            throw new BusinessException(ErrorCode.CHALLENGE_UNDER_REVIEW);

        // 즉시 ACTIVE 등록. uq_member 로 동시 INSERT는 1건만 성공, 나머지는 중복으로 변환.
        ChallengeMember joined = ChallengeMember.join(challengeId, userId, MemberStatus.ACTIVE);
        try {
            memberRepository.saveAndFlush(joined);
        } catch (DataIntegrityViolationException dup) {
            throw new BusinessException(ErrorCode.ALREADY_JOINED);
        }
        challengeRepository.incrementParticipantCount(challengeId);
        return joinResponse(c, MemberStatus.ACTIVE, joined.getSetupStatus());
    }

    // ===== §6 탈퇴 =====
    /**
     * 챌린지 탈퇴(본인). 패널티는 본인 success 이력 유무로 판정(있으면 트리거, 실제 가감은 매너 온도 스펙 소관).
     * 탈퇴 후 해당 챌린지 재참여 영구 불가(uq_member — LEFT 행 유지).
     *  - 종료된 챌린지: CHALLENGE_COMPLETED
     *  - OWNER: 탈퇴 불가. 참여자(본인 제외)가 있으면 DELEGATE_FIRST, 없으면 DELETE_INSTEAD 사유로 안내.
     */
    @Transactional
    public LeaveResponse leave(UUID userId, UUID challengeId) {
        Challenge c = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        if (c.getStatus() == ChallengeStatus.COMPLETED)
            throw new BusinessException(ErrorCode.CHALLENGE_COMPLETED);

        ChallengeMember me = memberRepository.findByChallengeIdAndUserId(challengeId, userId)
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        // 방장은 탈퇴 불가 — 참여자(본인 제외)가 있으면 위임 후, 없으면 삭제로 안내.
        if (me.isOwner()) {
            long others = memberRepository.countByChallengeIdAndStatus(challengeId, MemberStatus.ACTIVE) - 1;
            String reason = (others >= 1) ? "DELEGATE_FIRST" : "DELETE_INSTEAD";
            throw new BusinessException(ErrorCode.OWNER_CANNOT_LEAVE, reason);
        }

        // 본인 success 이력 유무로 패널티 트리거 판정(이미 반영된 미인증 패널티는 그대로 유지).
        boolean penaltyApplied = verificationDailyRepository
                .countByChallengeMemberIdAndStatus(me.getId(), VerificationStatus.SUCCESS) > 0;

        me.leave();   // ACTIVE → LEFT (dirty checking). 행은 남겨 재참여 금지.
        challengeRepository.decrementParticipantCount(challengeId);
        return new LeaveResponse(penaltyApplied);
    }

    /** 가입 응답 조립: 멤버 상태 + 셋업 상태 + 인증 스냅샷(필요 권한·방식). */
    private JoinResponse joinResponse(Challenge c, MemberStatus memberStatus, SetupStatus setupStatus) {
        return JoinResponse.of(
                memberStatus.name(),
                (setupStatus != null ? setupStatus : SetupStatus.PENDING_SETUP).name(),
                c.getVerificationConfig());
    }

    // ===== §7 멤버 목록 =====
    @Transactional(readOnly = true)
    public MemberListResponse listMembers(UUID viewerId, UUID challengeId) {
        Challenge c = loadActive(challengeId);
        // 가시성 게이트(§3-3): 비-OWNER는 모더레이션 통과(NONE/APPROVED)만 접근, 그 외는 존재 자체를 숨겨 404.
        if (!c.isVisibleTo(viewerId)) throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);

        // 승인제 폐기 — 현재 멤버(ACTIVE)만 반환. 탈퇴/제거(LEFT/REMOVED)는 목록에서 제외.
        List<ChallengeMember> members =
                memberRepository.findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE);

        // 사용자/매너 정보 일괄 조회 (N+1 방지)
        List<UUID> userIds = members.stream().map(ChallengeMember::getUserId).toList();
        Map<UUID, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, BigDecimal> mannerMap = reputationScoreRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(ReputationScore::getUserId, ReputationScore::getMannerTemperature));

        List<MemberListResponse.Member> dto = members.stream().map(m -> {
            User u = userMap.get(m.getUserId());
            // 익명 챌린지(§11.2)면 닉네임 마스킹 + 프로필 사진 숨김. 그 외엔 본인 가시성 규칙 적용.
            String visibleNick = (u != null) ? u.visibleNicknameTo(viewerId) : null;
            String nickname = c.getAnonymity().maskNickname(visibleNick);
            String profile = (u != null && !c.getAnonymity().isAnonymous())
                    ? u.visibleProfileImageTo(viewerId) : null;
            BigDecimal manner = mannerMap.getOrDefault(m.getUserId(), ReputationScore.INITIAL_TEMPERATURE);
            return new MemberListResponse.Member(
                    m.getUserId().toString(), nickname, profile,
                    m.getRole().name(), manner,
                    m.getJoinedAt() != null ? m.getJoinedAt().toString() : null);
        }).toList();

        return new MemberListResponse(challengeId.toString(), c.getParticipantCount(),
                c.getMaxParticipants(), dto);
    }

    // ===== §7-1 공동 관리자 임명/해제 =====
    /**
     * 역할 변경(PROMOTE: MEMBER→MANAGER / DEMOTE: MANAGER→MEMBER).
     *  - 임명·해제는 OWNER만. 단 MANAGER 본인의 DEMOTE(내려놓기)는 허용.
     *  - OWNER 역할은 이 API로 변경 불가(위임 API 사용).
     */
    @Transactional
    public RoleChangeResponse changeRole(UUID actorId, UUID challengeId, UUID targetUserId, String action) {
        Challenge c = loadActive(challengeId);
        ChallengeMember target = memberRepository.findByChallengeIdAndUserId(challengeId, targetUserId)
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        // OWNER 는 이 API로 못 바꾼다(위임 API 전용).
        if (target.isOwner()) throw new BusinessException(ErrorCode.CANNOT_CHANGE_OWNER_ROLE);

        MemberRole desired = switch (action == null ? "" : action) {
            case "PROMOTE" -> MemberRole.MANAGER;
            case "DEMOTE"  -> MemberRole.MEMBER;
            default -> throw new BusinessException(ErrorCode.INVALID_MEMBER_ACTION);
        };

        // 권한: OWNER는 임명·해제 모두 / 그 외엔 "본인 DEMOTE(내려놓기)"만.
        boolean selfDemote = "DEMOTE".equals(action) && actorId.equals(targetUserId);
        if (!c.isOwner(actorId) && !selfDemote)
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);

        if (target.getRole() == desired) throw new BusinessException(ErrorCode.ALREADY_IN_ROLE);

        target.changeRole(desired);   // 영속 상태 → dirty checking 으로 flush
        return new RoleChangeResponse(targetUserId.toString(), desired.name());
    }

    // ===== 헬퍼 =====
    private Challenge loadActive(UUID challengeId) {
        return challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }

    private void ensureOwner(Challenge c, UUID userId) {
        if (!c.isOwner(userId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
    }

    private BigDecimal mannerTemp(UUID userId) {
        return reputationScoreRepository.findById(userId)
                .map(ReputationScore::getMannerTemperature)
                .orElse(ReputationScore.INITIAL_TEMPERATURE);
    }
}