package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.JoinResponse;
import com.ruleup.ruleup_backend.challenge.dto.MemberActionResponse;
import com.ruleup.ruleup_backend.challenge.dto.MemberListResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.SetupStatus;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
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
 * 챌린지 멤버십 (스펙 3.6 ~ 3.8): 참여 신청 / 승인·거절 / 멤버 목록.
 *  - 참여(3.6): 그룹+기준이면 매너 검증 후 PENDING, 솔로/기준미설정이면 즉시 ACTIVE.
 *  - 승인(3.7): OWNER만. PENDING → ACTIVE(+count) / REMOVED.
 *  - 목록(3.8): 기본 ACTIVE. PENDING·ALL은 OWNER만 조회.
 */
@Service
@RequiredArgsConstructor
public class ChallengeMemberService {

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final ReputationScoreRepository reputationScoreRepository;

    // ===== 3.6 참여 신청 =====
    @Transactional
    public JoinResponse join(UUID userId, UUID challengeId) {
        Challenge c = loadActive(challengeId);
        // 모더레이션 게이트(§5.1): APPROVED 가 아니면 가입 차단.
        if (!c.isApproved()) throw new BusinessException(ErrorCode.CHALLENGE_UNDER_REVIEW);

        // 한 챌린지 1회 멤버십(uq_member). 기존 행이 ACTIVE/PENDING이면 중복, LEFT/REMOVED면 재참여 허용.
        ChallengeMember existing = memberRepository.findByChallengeIdAndUserId(challengeId, userId).orElse(null);
        if (existing != null && (existing.isActive() || existing.isPending()))
            throw new BusinessException(ErrorCode.ALREADY_JOINED);

        MemberStatus initial = decideInitialStatus(c, userId);

        if (existing != null) {
            // 재참여: LEFT/REMOVED → initial 로 CAS. 동시 재참여는 행 잠금으로 직렬화되어 1건만 성사.
            int updated = memberRepository.compareAndSetStatus(
                    existing.getId(), List.of(MemberStatus.LEFT, MemberStatus.REMOVED), initial);
            if (updated == 0) throw new BusinessException(ErrorCode.ALREADY_JOINED);
            if (initial == MemberStatus.ACTIVE) challengeRepository.incrementParticipantCount(challengeId);
            // 재참여는 기존 행의 setupStatus를 그대로 둔다(이전에 셋업했으면 READY 유지).
            return joinResponse(c, initial, existing.getSetupStatus());
        }

        // 최초 참여: uq_member 로 동시 INSERT는 1건만 성공, 나머지는 중복으로 변환.
        ChallengeMember joined = ChallengeMember.join(challengeId, userId, initial);
        try {
            memberRepository.saveAndFlush(joined);
        } catch (DataIntegrityViolationException dup) {
            throw new BusinessException(ErrorCode.ALREADY_JOINED);
        }
        if (initial == MemberStatus.ACTIVE) challengeRepository.incrementParticipantCount(challengeId);
        return joinResponse(c, initial, joined.getSetupStatus());
    }

    /** 가입 응답 조립: 멤버 상태 + 셋업 상태 + 인증 스냅샷(필요 권한·방식). */
    private JoinResponse joinResponse(Challenge c, MemberStatus memberStatus, SetupStatus setupStatus) {
        return JoinResponse.of(
                memberStatus.name(),
                (setupStatus != null ? setupStatus : SetupStatus.PENDING_SETUP).name(),
                c.getVerificationConfig());
    }

    /** 그룹+기준 설정 → 매너 검증 후 PENDING. 솔로 또는 기준 미설정 → ACTIVE. */
    private MemberStatus decideInitialStatus(Challenge c, UUID userId) {
        if (c.isGroup() && c.getMinMannerTemperature() != null) {
            BigDecimal myManner = mannerTemp(userId);
            if (myManner.compareTo(c.getMinMannerTemperature()) < 0)
                throw new BusinessException(ErrorCode.MANNER_TEMPERATURE_BELOW_MINIMUM);
            return MemberStatus.PENDING;     // 기준은 통과했지만 운영자 승인 대기
        }
        return MemberStatus.ACTIVE;
    }

    // ===== 3.7 승인/거절 (OWNER) =====
    @Transactional
    public MemberActionResponse handleApplication(UUID ownerId, UUID challengeId, UUID targetUserId, String action) {
        Challenge c = loadActive(challengeId);
        ensureOwner(c, ownerId);

        ChallengeMember member = memberRepository.findByChallengeIdAndUserId(challengeId, targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (action == null) throw new BusinessException(ErrorCode.INVALID_MEMBER_ACTION);
        return switch (action) {
            // 둘 다 PENDING 신청에 대한 처리. CAS로 동시 처리 시 1회만 성사(참여자 수 중복 증가 방지).
            case "APPROVE" -> transitionFromPending(challengeId, targetUserId, member, MemberStatus.ACTIVE, true);
            case "REJECT"  -> transitionFromPending(challengeId, targetUserId, member, MemberStatus.REMOVED, false);
            default -> throw new BusinessException(ErrorCode.INVALID_MEMBER_ACTION);
        };
    }

    /**
     * PENDING → to 로 원자적 전이. 성사(1행)면 (옵션) 참여자 수 증가 후 to 반환.
     * 이미 처리됐으면(0행) 현재 상태를 재조회해 멱등 응답.
     */
    private MemberActionResponse transitionFromPending(UUID challengeId, UUID targetUserId,
                                                       ChallengeMember member, MemberStatus to, boolean bumpCount) {
        int updated = memberRepository.compareAndSetStatus(
                member.getId(), List.of(MemberStatus.PENDING), to);
        if (updated == 1) {
            if (bumpCount) challengeRepository.incrementParticipantCount(challengeId);
            return new MemberActionResponse(to.name());
        }
        MemberStatus current = memberRepository.findByChallengeIdAndUserId(challengeId, targetUserId)
                .map(ChallengeMember::getStatus).orElse(member.getStatus());
        return new MemberActionResponse(current.name());
    }

    // ===== 3.8 멤버 목록 =====
    @Transactional(readOnly = true)
    public MemberListResponse listMembers(UUID viewerId, UUID challengeId, String statusFilter) {
        Challenge c = loadActive(challengeId);

        String filter = (statusFilter == null || statusFilter.isBlank())
                ? "ACTIVE" : statusFilter.toUpperCase();

        // PENDING·ALL은 OWNER만 (일반 참여자에게 신청자 노출 X)
        if (("PENDING".equals(filter) || "ALL".equals(filter)) && !c.isOwner(viewerId))
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);

        List<ChallengeMember> members = switch (filter) {
            case "ALL"     -> memberRepository.findByChallengeIdOrderByJoinedAtAsc(challengeId);
            case "PENDING" -> memberRepository.findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.PENDING);
            case "ACTIVE"  -> memberRepository.findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE);
            default        -> throw new BusinessException(ErrorCode.INVALID_REQUEST);
        };

        // 사용자/매너 정보 일괄 조회 (N+1 방지)
        List<UUID> userIds = members.stream().map(ChallengeMember::getUserId).toList();
        Map<UUID, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, BigDecimal> mannerMap = reputationScoreRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(ReputationScore::getUserId, ReputationScore::getMannerTemperature));

        List<MemberListResponse.Member> dto = members.stream().map(m -> {
            User u = userMap.get(m.getUserId());
            // 검수 전/거절이면 타인에게는 임시 닉네임·사진 숨김(본인이 볼 땐 본인 값).
            String nickname = (u != null) ? u.visibleNicknameTo(viewerId) : null;
            String profile = (u != null) ? u.visibleProfileImageTo(viewerId) : null;
            BigDecimal manner = mannerMap.getOrDefault(m.getUserId(), ReputationScore.INITIAL_TEMPERATURE);
            return new MemberListResponse.Member(
                    m.getUserId().toString(), nickname, profile,
                    m.getRole().name(), m.getStatus().name(), manner,
                    m.getJoinedAt() != null ? m.getJoinedAt().toString() : null);
        }).toList();

        return new MemberListResponse(challengeId.toString(), c.getParticipantCount(), dto);
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