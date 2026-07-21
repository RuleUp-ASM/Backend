package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.room.RoomAuthority;
import com.ruleup.ruleup_backend.room.domain.Notice;
import com.ruleup.ruleup_backend.room.dto.RoomDtos;
import com.ruleup.ruleup_backend.room.repository.NoticeReadRepository;
import com.ruleup.ruleup_backend.room.repository.NoticeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 랭킹 조회 + 방 홈 일괄 조회(방 내부기능 §7.3, room). ACTIVE 멤버 전용(RoomAuthority). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int TOP_N = 3;

    private final RoomAuthority roomAuthority;
    private final RankingService rankingService;
    private final ChallengeMemberRepository memberRepository;
    private final ReputationScoreRepository reputationScoreRepository;
    private final NoticeRepository noticeRepo;
    private final NoticeReadRepository noticeReadRepo;

    // ===== 랭킹 =====
    public RoomDtos.RankingResponse ranking(UUID userId, UUID challengeId) {
        Challenge c = roomAuthority.requireMember(challengeId, userId);
        List<RankingService.Ranked> ranked = rankingService.rank(c, userId);

        List<RoomDtos.RankingResponse.Rank> rankings = ranked.stream()
                .map(r -> new RoomDtos.RankingResponse.Rank(
                        r.rank(), r.userId().toString(), r.nickname(), r.progressRate(), r.successDays()))
                .toList();

        RoomDtos.RankingResponse.MyRank myRank = null;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).userId().equals(userId)) {
                BigDecimal gap = (i == 0) ? BigDecimal.ZERO.setScale(2)
                        : ranked.get(i - 1).progressRate().subtract(ranked.get(i).progressRate());
                myRank = new RoomDtos.RankingResponse.MyRank(
                        ranked.get(i).rank(), ranked.get(i).progressRate(), gap);
                break;
            }
        }
        return new RoomDtos.RankingResponse(rankings, myRank);
    }

    // ===== 방 홈 =====
    public RoomDtos.RoomResponse room(UUID userId, UUID challengeId) {
        Challenge c = roomAuthority.requireMember(challengeId, userId);
        ChallengeMember me = roomAuthority.requireMembership(challengeId, userId);

        List<ChallengeMember> actives =
                memberRepository.findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE);

        RoomDtos.RoomResponse.Summary summary = new RoomDtos.RoomResponse.Summary(
                c.getTitle(), avgCompletion(actives), avgManner(actives),
                remainingDays(c), actives.size());

        Notice pinned = noticeRepo.findByChallengeIdAndPinnedTrue(challengeId).orElse(null);
        RoomDtos.RoomResponse.PinnedNotice pinnedDto = (pinned == null) ? null
                : new RoomDtos.RoomResponse.PinnedNotice(
                        pinned.getId().toString(), pinned.getTitle(), pinned.isPinned(),
                        pinned.getCreatedAt().toString(),
                        noticeReadRepo.existsByNoticeIdAndUserId(pinned.getId(), userId));

        long total = noticeRepo.countByChallengeId(challengeId);
        long read = noticeReadRepo.countByChallengeIdAndUserId(challengeId, userId);
        int unread = (int) Math.max(0, total - read);

        List<RoomDtos.RoomResponse.TopRank> top = rankingService.rank(c, userId).stream()
                .limit(TOP_N)
                .map(r -> new RoomDtos.RoomResponse.TopRank(r.rank(), r.userId().toString(), r.nickname(), r.progressRate()))
                .toList();

        String myRole = c.isOwner(userId) ? "OWNER" : "MEMBER";
        String todayStatus = (me.getTodayStatus() != null) ? me.getTodayStatus().name() : null;

        return new RoomDtos.RoomResponse(myRole, summary, pinnedDto, unread, top, todayStatus);
    }

    /** 완주율 = ACTIVE 멤버 진행률 평균(정수). */
    private int avgCompletion(List<ChallengeMember> actives) {
        if (actives.isEmpty()) return 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (ChallengeMember m : actives)
            sum = sum.add(m.getProgressRate() != null ? m.getProgressRate() : BigDecimal.ZERO);
        return sum.divide(BigDecimal.valueOf(actives.size()), 0, RoundingMode.HALF_UP).intValue();
    }

    /** 평균 매너 온도 = ACTIVE 멤버 온도 평균(소수 둘째). */
    private BigDecimal avgManner(List<ChallengeMember> actives) {
        if (actives.isEmpty()) return null;
        List<UUID> userIds = actives.stream().map(ChallengeMember::getUserId).toList();
        Map<UUID, BigDecimal> temps = reputationScoreRepository.findAllById(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(ReputationScore::getUserId, ReputationScore::getMannerTemperature));
        BigDecimal sum = BigDecimal.ZERO;
        for (UUID id : userIds)
            sum = sum.add(temps.getOrDefault(id, ReputationScore.INITIAL_TEMPERATURE));
        return sum.divide(BigDecimal.valueOf(userIds.size()), 2, RoundingMode.HALF_UP);
    }

    private int remainingDays(Challenge c) {
        long days = ChronoUnit.DAYS.between(LocalDate.now(KST), c.getEndDate());
        return (int) Math.max(0, days);
    }
}
