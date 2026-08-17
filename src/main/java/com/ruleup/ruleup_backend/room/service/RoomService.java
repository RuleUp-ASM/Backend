package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.room.RoomAuthority;
import com.ruleup.ruleup_backend.room.dto.RoomDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final RoomAuthority authority;
    private final RankingService rankingService;
    private final ChallengeMemberRepository memberRepository;

    public RoomDtos.RankingResponse ranking(UUID userId, UUID challengeId) {
        Challenge challenge = authority.requireMember(challengeId, userId);
        List<RankingService.Ranked> rows = rankingService.rank(challenge, userId);
        BigDecimal firstRate = rows.stream().filter(RankingService.Ranked::ranked)
                .map(RankingService.Ranked::successRate).findFirst().orElse(null);
        RankingService.Ranked mine = rows.stream().filter(r -> r.userId().equals(userId)).findFirst().orElseThrow();
        BigDecimal gap = mine.ranked() && firstRate != null ? firstRate.subtract(mine.successRate()) : null;
        RoomDtos.RankingResponse.Me me = new RoomDtos.RankingResponse.Me(
                mine.rank(), mine.ranked(), mine.successRate(), mine.participations(), gap);
        List<RoomDtos.RankingResponse.Item> items = rows.stream()
                .map(r -> new RoomDtos.RankingResponse.Item(r.rank(),
                        new RoomDtos.User(r.userId().toString(), r.nickname(), r.profileImageUrl(), r.blocked()),
                        r.successRate(), r.successCount(), r.participations())).toList();
        return new RoomDtos.RankingResponse(me, items);
    }

    public RoomDtos.RoomResponse room(UUID userId, UUID challengeId) {
        Challenge challenge = authority.requireMember(challengeId, userId);
        ChallengeMember me = authority.requireMembership(challengeId, userId);
        List<ChallengeMember> active = memberRepository
                .findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE);
        RoomDtos.RoomResponse.Summary summary = new RoomDtos.RoomResponse.Summary(
                challenge.getTitle(), roomRate(active), remainingDays(challenge), active.size(),
                challenge.getMaxParticipants());
        List<RoomDtos.RoomResponse.TopRank> top = rankingService.rank(challenge, userId).stream()
                .filter(RankingService.Ranked::ranked).limit(3)
                .map(r -> new RoomDtos.RoomResponse.TopRank(r.rank(), r.userId().toString(), r.nickname(),
                        r.profileImageUrl(), r.successRate(), r.blocked())).toList();
        return new RoomDtos.RoomResponse(me.isOwner() ? "OWNER" : "MEMBER", challenge.getOwnerType().name(), summary,
                top, me.getTodayStatus() == null ? null : me.getTodayStatus().name(), null);
    }

    /**
     * 방 전체 성공률. 판정이 한 건도 없으면 <b>0 이 아니라 null</b>이다 — 갓 만들어진 방과
     * "전원이 실패한 방"은 화면에서 전혀 다르게 읽혀야 하는데 0.0000 으로 내려보내면 구분이 사라진다.
     */
    private BigDecimal roomRate(List<ChallengeMember> members) {
        int success = members.stream().mapToInt(ChallengeMember::getSuccessDays).sum();
        int total = members.stream().mapToInt(m -> m.getSuccessDays() + m.getFailDays()).sum();
        return total == 0 ? null
                : BigDecimal.valueOf(success).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private int remainingDays(Challenge challenge) {
        return (int) Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(KST), challenge.getEndDate()));
    }
}
