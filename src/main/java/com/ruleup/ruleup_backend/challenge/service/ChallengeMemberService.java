package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.JoinResponse;
import com.ruleup.ruleup_backend.challenge.dto.MemberActionResponse;
import com.ruleup.ruleup_backend.challenge.dto.MemberListResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.reputation.ReputationScore;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.user.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
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

        // 한 챌린지 1회 멤버십(uq_member). 기존 행이 ACTIVE/PENDING이면 중복, LEFT/REMOVED면 재참여 허용.
        ChallengeMember existing = memberRepository.findByChallengeIdAndUserId(challengeId, userId).orElse(null);
        if (existing != null && (existing.isActive() || existing.isPending()))
            throw new BusinessException(ErrorCode.ALREADY_JOINED);

        MemberStatus initial = decideInitialStatus(c, userId);

        if (existing != null) {
            // 재참여: 기존 행 상태만 갱신 (새 INSERT는 uq_member에 막힘)
            if (initial == MemberStatus.ACTIVE) { existing.approve(); c.increaseParticipantCount(); }
            else                                { reactivateAsPending(existing); }
            return new JoinResponse(existing.getStatus().name());
        }

        ChallengeMember member = ChallengeMember.join(challengeId, userId, initial);
        memberRepository.save(member);
        if (initial == MemberStatus.ACTIVE) c.increaseParticipantCount();

        return new JoinResponse(initial.name());
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

    private void reactivateAsPending(ChallengeMember m) {
        // LEFT/REMOVED → PENDING 재신청. approve()/reject()만으론 PENDING 복귀가 안 되어 별도 처리.
        m.rejoinAsPending();
    }

    // ===== 3.7 승인/거절 (OWNER) =====
    @Transactional
    public MemberActionResponse handleApplication(UUID ownerId, UUID challengeId, UUID targetUserId, String action) {
        Challenge c = loadActive(challengeId);
        ensureOwner(c, ownerId);

        ChallengeMember member = memberRepository.findByChallengeIdAndUserId(challengeId, targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (action == null) throw new BusinessException(ErrorCode.INVALID_MEMBER_ACTION);
        switch (action) {
            case "APPROVE" -> {
                if (member.isPending()) {        // 멱등: 이미 ACTIVE면 count 중복 증가 방지
                    member.approve();
                    c.increaseParticipantCount();
                }
            }
            case "REJECT" -> member.reject();
            default -> throw new BusinessException(ErrorCode.INVALID_MEMBER_ACTION);
        }
        return new MemberActionResponse(member.getStatus().name());
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

        Anonymity anonymity = c.getAnonymity();
        List<MemberListResponse.Member> dto = members.stream().map(m -> {
            User u = userMap.get(m.getUserId());
            String nickname = (u != null) ? anonymity.maskNickname(u.getNickname()) : null;
            String profile = (u != null && !anonymity.isAnonymous()) ? u.getProfileImageUrl() : null;
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