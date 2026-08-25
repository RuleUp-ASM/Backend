package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.room.RoomAuthority;
import com.ruleup.ruleup_backend.room.dto.RoomDtos;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 자정 직후 유예 구간(KST 00~03시). 어젯밤 신호가 아직 확정 배치를 타지 않은 시간대라
     * 미확정(PENDING)을 "진행 중"이 아니라 <b>확인 중(CHECKING)</b>으로 보여준다 — 이미 끝난 하루를
     * 아직 할 수 있는 것처럼 그리면 안 된다.
     */
    private static final int GRACE_END_HOUR = 3;

    private final RoomAuthority authority;
    private final RankingService rankingService;
    private final ChallengeMemberRepository memberRepository;
    private final VerificationDailyRepository verificationRepository;

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
        ZonedDateTime now = ZonedDateTime.now(KST);
        RoomDtos.RoomResponse.Summary summary = new RoomDtos.RoomResponse.Summary(
                challenge.getTitle(), challenge.getWeeklyCount(), roomRate(active),
                remainingDays(challenge, now.toLocalDate()), active.size(), challenge.getMaxParticipants());
        List<RoomDtos.RoomResponse.TopRank> top = rankingService.rank(challenge, userId).stream()
                .filter(RankingService.Ranked::ranked).limit(3)
                .map(r -> new RoomDtos.RoomResponse.TopRank(r.rank(), r.userId().toString(), r.nickname(),
                        r.profileImageUrl(), r.successRate(), r.blocked())).toList();
        RoomDtos.RoomResponse.MyWeekly weekly = myWeekly(challenge, me, now.toLocalDate());
        return new RoomDtos.RoomResponse(me.isOwner() ? "OWNER" : "MEMBER", challenge.getOwnerType().name(), summary,
                top, weekly, todayStatus(challenge, me, weekly, now), null);
    }

    /**
     * 이번 주 사이클의 내 진행도. 사이클 1주차는 챌린지 시작일에 열리고 이후 7일 단위로 굴러간다
     * (정책 §1 — 요일 지정은 없다). 사이클 중간에 들어온 사람은 그 주를 통째로 평가받으면 불리하므로
     * 다음 경계부터 판정되며({@link ChallengeCycle#countFrom}), 그때까지는 {@code judging=false · done=0} 이다.
     */
    private RoomDtos.RoomResponse.MyWeekly myWeekly(Challenge challenge, ChallengeMember me, LocalDate today) {
        LocalDate start = challenge.getStartDate();
        boolean started = !today.isBefore(start);
        // 시작 전 방은 아직 사이클이 열리지 않았으므로 1주차 경계를 그대로 보여준다.
        LocalDate weekStart = started
                ? start.plusDays((ChronoUnit.DAYS.between(start, today) / ChallengeCycle.CYCLE_DAYS)
                        * ChallengeCycle.CYCLE_DAYS)
                : start;
        LocalDate weekEnd = weekStart.plusDays(ChallengeCycle.CYCLE_DAYS - 1L);
        LocalDate countFrom = ChallengeCycle.countFrom(start, LocalDate.ofInstant(me.getJoinedAt(), KST));
        boolean judging = started
                && challenge.getStatus() == ChallengeStatus.ACTIVE
                && !today.isBefore(countFrom);
        int done = judging
                ? (int) verificationRepository.countByChallengeMemberIdAndStatusAndTargetDateBetween(
                        me.getId(), VerificationStatus.SUCCESS, weekStart, weekEnd)
                : 0;
        return new RoomDtos.RoomResponse.MyWeekly(done, weekStart.toString(), weekEnd.toString(), judging);
    }

    /**
     * 화면 뱃지용 오늘 상태. 저장 값({@link VerificationStatus})은 판정 파이프라인의 어휘라 그대로 내리면
     * 클라이언트가 판정 파이프라인 어휘까지 알 이유는 없다 — 여기서 화면 어휘로 좁힌다.
     * 확정된 값이 없을 때만 사이클 정보로 대상 여부를 가른다.
     */
    private String todayStatus(Challenge challenge, ChallengeMember me,
                               RoomDtos.RoomResponse.MyWeekly weekly, ZonedDateTime now) {
        VerificationStatus cached = me.getTodayStatus();
        if (cached != null) {
            switch (cached) {
                case SUCCESS -> { return "DONE"; }
                // 확정된 실패만 실패로 보인다 — 이의로 뒤집히면 결과 알림으로 따로 안내한다.
                case FAILED -> { return "FAILED"; }
                case NOT_TARGET, NOT_REQUIRED -> { return "NOT_TARGET"; }
                case PENDING -> { /* 아래 사이클 판정으로 넘어간다 */ }
            }
        }
        int weeklyCount = challenge.getWeeklyCount() == null ? ChallengeCycle.CYCLE_DAYS : challenge.getWeeklyCount();
        // 이번 주 몫을 이미 채웠으면 오늘은 더 할 게 없다 — 요일 지정이 없으므로 이것이 유일한 비대상 조건이다.
        if (!weekly.judging() || weekly.done() >= weeklyCount) return "NOT_TARGET";
        return now.getHour() < GRACE_END_HOUR ? "CHECKING" : "IN_PROGRESS";
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

    private int remainingDays(Challenge challenge, LocalDate today) {
        return (int) Math.max(0, ChronoUnit.DAYS.between(today, challenge.getEndDate()));
    }
}
