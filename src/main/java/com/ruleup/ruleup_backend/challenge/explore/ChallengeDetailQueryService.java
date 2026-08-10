package com.ruleup.ruleup_backend.challenge.explore;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.JoinBlockReason;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.dto.ChallengeDetailResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.repository.UserChallengeCounterRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 공개 상세 조회 (탐색 백엔드 테크스펙 §11-4).
 *
 * <p><b>존재 은닉</b>이 이 화면의 첫 번째 규칙이다. 없는 방·비공개 방의 비멤버·타인의 솔로 방은
 * 전부 같은 404 로 답한다 — 403 과 404 를 나눠 주면 "그 방은 존재한다"는 사실이 새기 때문이다.
 *
 * <p>{@code eligible}·{@code joinBlockReason} 은 사용자와 현재 상태에 의존하므로 캐시하지 않고
 * 매 요청 계산한다. 차단 사유는 가입 API 와 <b>같은 enum</b>을 써서 미리보기와 실제 결과가 어긋나지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeDetailQueryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int FREE_CONCURRENT_LIMIT = 3;

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final UserChallengeCounterRepository counterRepository;
    private final UserRepository userRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final RoutineCatalog catalog;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public ChallengeDetailResponse detail(UUID viewerId, UUID challengeId) {
        Challenge c = challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        ChallengeMember myMembership =
                memberRepository.findByChallengeIdAndUserId(challengeId, viewerId).orElse(null);
        boolean isActiveMember = myMembership != null && myMembership.isActive();
        boolean isOwner = c.isOwner(viewerId);
        requireVisible(c, isOwner, isActiveMember);

        Tier myTier = displayTier(viewerId);
        boolean eligible = c.getMinTier() == null || myTier.ordinal() >= c.getMinTier().ordinal();
        long activeCount = memberRepository.countByChallengeIdAndStatus(challengeId, MemberStatus.ACTIVE);
        boolean full = c.getMaxParticipants() != null && activeCount >= c.getMaxParticipants();

        JoinBlockReason blockReason =
                blockReason(c, viewerId, myMembership, isOwner, activeCount, eligible);
        LocalDate today = LocalDate.now(KST);

        return new ChallengeDetailResponse(
                c.getId().toString(),
                isOwner ? c.getTitle() : c.publicTitle(),
                isOwner ? c.getDescription() : c.publicDescription(),
                isOwner ? c.getImageUrl() : c.publicImageUrl(),
                c.getCategory(),
                c.getParticipationType().name(),
                c.getVisibility(),
                c.getStatus().name(),
                owner(c),
                c.getOwnerType().name(),
                c.getParticipantCount(),
                c.getMaxParticipants(),
                full,
                new ChallengeDetailResponse.Period(
                        c.getStartDate().toString(), c.getEndDate().toString(),
                        (int) ChronoUnit.DAYS.between(today, c.getEndDate())),
                verification(c),
                stats(challengeId),
                new ChallengeDetailResponse.Gate(
                        (c.getMinTier() != null) ? c.getMinTier().name() : null, myTier.name(), eligible),
                (blockReason != null) ? blockReason.name() : null,
                (blockReason == JoinBlockReason.REJOIN_COOLDOWN)
                        ? myMembership.getRejoinAvailableAt().toString() : null,
                ChallengeCycle.startsNextCycle(c.getStartDate(), today) ? "NEXT_CYCLE" : "IMMEDIATE",
                cloneable(c),
                myRole(c, viewerId, myMembership, isOwner, isActiveMember),
                isOwner ? new ChallengeDetailResponse.Moderation(
                        c.getModerationTitle().name(), c.getModerationDescription().name(),
                        c.getModerationImage().name()) : null);
    }

    /** 볼 수 있는 방인가. 아니면 존재를 숨긴 채 404. */
    private void requireVisible(Challenge c, boolean isOwner, boolean isActiveMember) {
        if (isOwner || isActiveMember) return;
        boolean solo = c.getParticipationType() != ParticipationType.GROUP;
        boolean privateRoom = "PRIVATE".equals(c.getVisibility());
        if (solo || privateRoom) throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
    }

    /** 복제 가능 여부 — 공개 그룹만. 비공개·솔로는 남의 설정을 가져갈 대상이 아니다. */
    private boolean cloneable(Challenge c) {
        return c.getParticipationType() == ParticipationType.GROUP && "PUBLIC".equals(c.getVisibility());
    }

    private JoinBlockReason blockReason(Challenge c, UUID viewerId, ChallengeMember myMembership,
                                        boolean isOwner, long activeCount, boolean eligible) {
        if (c.getStatus() == ChallengeStatus.COMPLETED) return JoinBlockReason.CHALLENGE_COMPLETED;
        if (isOwner || (myMembership != null && myMembership.isActive())) return JoinBlockReason.ALREADY_JOINED;
        if (c.isGroup() && "PRIVATE".equals(c.getVisibility())) return JoinBlockReason.PRIVATE_INVITE_ONLY;
        if (myMembership != null) {
            Instant availableAt = myMembership.getRejoinAvailableAt();
            if (availableAt != null && Instant.now().isBefore(availableAt))
                return JoinBlockReason.REJOIN_COOLDOWN;
        }
        int activeJoinCount = counterRepository.findById(viewerId)
                .map(counter -> counter.getActiveJoinCount()).orElse(0);
        if (activeJoinCount >= FREE_CONCURRENT_LIMIT) return JoinBlockReason.FREE_LIMIT;
        if (c.getMaxParticipants() != null && activeCount >= c.getMaxParticipants()) return JoinBlockReason.FULL;
        if (!eligible) return JoinBlockReason.TIER_GATE;
        return null;
    }

    private ChallengeDetailResponse.Owner owner(Challenge c) {
        if (c.isBotOwned() || c.getCreatorId() == null) return null;   // 봇방장이면 사람 방장이 없다
        return userRepository.findById(c.getCreatorId())
                .map(u -> new ChallengeDetailResponse.Owner(
                        c.getCreatorId().toString(), c.getAnonymity().maskNickname(u.getNickname())))
                .orElse(null);
    }

    private ChallengeDetailResponse.Verification verification(Challenge c) {
        var config = c.getVerificationConfig();
        boolean auto = config != null && config.selectedMethod() == SelectedMethod.AUTO;
        RoutineTemplate template = (c.getTemplateId() != null)
                ? catalog.findById(c.getTemplateId()).orElse(null) : null;
        return new ChallengeDetailResponse.Verification(
                auto ? "AUTO" : "MANUAL",
                auto && template != null ? template.getVerificationMethod() : "SELF_CHECK",
                template != null ? template.getDescription() : null,
                (config != null && config.requiredPermissions() != null)
                        ? config.requiredPermissions() : List.of());
    }

    /** 표본 미달이면 두 값 모두 null 로 내려간다 — 판정은 Projection 이 이미 끝냈다. */
    private ChallengeDetailResponse.Stats stats(UUID challengeId) {
        return jdbc.query("SELECT completion_rate, retention_rate FROM challenge_stats WHERE challenge_id = ?",
                        (rs, i) -> new ChallengeDetailResponse.Stats(
                                (Double) rs.getObject("completion_rate", Double.class),
                                (Double) rs.getObject("retention_rate", Double.class)),
                        toBytes(challengeId))
                .stream().findFirst()
                .orElse(new ChallengeDetailResponse.Stats(null, null));
    }

    private String myRole(Challenge c, UUID viewerId, ChallengeMember m,
                          boolean isOwner, boolean isActiveMember) {
        if (isOwner) return "OWNER";
        return isActiveMember ? m.getRole().name() : "NONE";
    }

    private Tier displayTier(UUID userId) {
        Tier tier = scoreSummaryRepository.findById(userId).map(s -> s.getDisplayTier()).orElse(Tier.BRONZE);
        return (tier == Tier.UNRANKED) ? Tier.BRONZE : tier;
    }

    private static byte[] toBytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
