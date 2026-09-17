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
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import com.ruleup.ruleup_backend.score.repository.UserScoreSummaryRepository;
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

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final RoutineCatalog catalog;
    private final JdbcTemplate jdbc;
    private final com.ruleup.ruleup_backend.room.service.ChallengeRejoinPolicy rejoinPolicy;
    private final com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeHistoryQueryService history;
    private final com.ruleup.ruleup_backend.challenge.view.ChallengeMasking masking;
    private final com.ruleup.ruleup_backend.report.BlockService blocks;

    @Transactional(readOnly = true)
    public ChallengeDetailResponse detail(UUID viewerId, UUID challengeId) {
        if (history.archived(challengeId)) return history.detail(viewerId, challengeId);
        Challenge c = challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        ChallengeMember myMembership =
                memberRepository.findByChallengeIdAndUserId(challengeId, viewerId).orElse(null);
        boolean isActiveMember = myMembership != null && myMembership.isActive();
        boolean isOwner = c.isOwner(viewerId);
        requireVisible(c, isOwner, isActiveMember);
        // 신고로 <b>숨긴</b> 방(미참여)은 상세로도 열리지 않는다. 가림이 목록 쿼리에만 걸려 있어
        // 딥링크·알림·초대 링크로 그대로 다시 노출됐다(QA REP-05). 참여 중이면 나가는 것이 먼저라
        // 방을 없애지 않고 표시값만 가린다 — 그건 아래 ChallengeView 가 한다.
        if (!isActiveMember && !isOwner && masking.isMasked(viewerId, challengeId)) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }

        Tier myTier = displayTier(viewerId);
        boolean eligible = c.getMinTier() == null || myTier.ordinal() >= c.getMinTier().ordinal();
        long activeCount = memberRepository.countByChallengeIdAndStatus(challengeId, MemberStatus.ACTIVE);
        boolean full = c.getMaxParticipants() != null && activeCount >= c.getMaxParticipants();

        JoinBlockReason blockReason =
                blockReason(c, viewerId, myMembership, isOwner, activeCount, eligible);
        LocalDate today = LocalDate.now(KST);

        var view = com.ruleup.ruleup_backend.challenge.view.ChallengeView.of(
                c, isOwner, masking.isMasked(viewerId, challengeId));

        return new ChallengeDetailResponse(
                c.getId().toString(),
                view.title(),
                view.description(),
                view.imageUrl(),
                c.getCategory(),
                c.getParticipationType().name(),
                c.getVisibility(),
                c.getStatus().name(),
                owner(c, viewerId),
                c.getOwnerType().name(),
                // isFull 을 실시간 COUNT 로 재면서 참여자 수만 비동기 표시값을 내리면
                // 「0명인데 마감」 같은 카드가 나온다. 한 요청 안에서는 같은 원천을 본다.
                (int) activeCount,
                c.getMaxParticipants(),
                c.getWeeklyCount(),
                full,
                new ChallengeDetailResponse.Period(
                        c.getStartDate().toString(), c.getEndDate() == null ? null : c.getEndDate().toString(),
                        c.getEndDate() == null ? null : (int) ChronoUnit.DAYS.between(today, c.getEndDate())),
                verification(c),
                stats(challengeId),
                new ChallengeDetailResponse.Gate(
                        (c.getMinTier() != null) ? c.getMinTier().name() : null, myTier.name(), eligible),
                (blockReason != null) ? blockReason.name() : null,
                (blockReason == JoinBlockReason.REJOIN_COOLDOWN)
                        ? rejoinPolicy.availableAt(challengeId, viewerId, myMembership).toString() : null,
                ChallengeCycle.startsNextCycle(c.getStartDate(), today) ? "NEXT_CYCLE" : "IMMEDIATE",
                cloneable(c),
                // 방장은 멤버 행과 무관하게 참여 중이다(솔로 방·시작 전 방도 마찬가지).
                isOwner || isActiveMember,
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
        {
            // 가입 게이트와 같은 순서 — 영구 차단이 재입장 대기보다 먼저다(ChallengeMemberService.join ③).
            if (rejoinPolicy.permanentlyBanned(c.getId(), viewerId) || (myMembership != null && myMembership.isRejoinBanned())) return JoinBlockReason.PERMANENT_BAN;
            Instant availableAt = rejoinPolicy.availableAt(c.getId(), viewerId, myMembership);
            if (availableAt != null && Instant.now().isBefore(availableAt))
                return JoinBlockReason.REJOIN_COOLDOWN;
        }
        // 동시 참여 개수 상한은 탐색 스펙 개정으로 사라졌다(공통 5-1) — 버튼을 미리 잠글 이유도 없다.
        if (c.getMaxParticipants() != null && activeCount >= c.getMaxParticipants()) return JoinBlockReason.FULL;
        if (!eligible) return JoinBlockReason.TIER_GATE;
        return null;
    }

    /**
     * 방장 표시. <b>차단한 사람이면 멤버 목록과 같은 임시 닉네임으로 가린다.</b>
     *
     * <p>멤버 목록·랭킹·스레드는 이미 {@code blockedUsers} 로 가리고 있었는데 여기만 빠져 있어,
     * 방장을 신고해 차단해도 정보 탭의 「방장 ○○○」에 실명이 그대로 남았다(QA REP-04).
     * 한 화면 안에서 같은 사람이 목록에서는 가려지고 진행 정보에서는 보이는 상태였다.
     *
     * <p>또 {@code getNickname()} 이 아니라 {@code visibleNicknameTo} 를 쓴다 — 전자는 <b>심사 전
     * 신청 닉네임</b>이라, 심사에 걸린 닉네임이 남의 화면에 그대로 나갔다.
     */
    private ChallengeDetailResponse.Owner owner(Challenge c, UUID viewerId) {
        if (c.isBotOwned() || c.getCreatorId() == null) return null;   // 봇방장이면 사람 방장이 없다
        boolean blocked = blocks.isUserBlocked(viewerId, c.getCreatorId());
        return userRepository.findById(c.getCreatorId())
                .map(u -> new ChallengeDetailResponse.Owner(
                        c.getCreatorId().toString(),
                        blocked ? u.deriveTempNickname()
                                : c.getAnonymity().maskNickname(u.visibleNicknameTo(viewerId))))
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
        return isActiveMember ? "MEMBER" : "NONE";
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
