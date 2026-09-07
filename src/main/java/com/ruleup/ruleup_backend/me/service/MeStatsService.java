package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.me.CompletionPolicy;
import com.ruleup.ruleup_backend.me.dto.MeStatsResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import com.ruleup.ruleup_backend.score.ScoreTransactionRepository;
import com.ruleup.ruleup_backend.score.domain.ScoreTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 통계 리포트(GET /me/stats) — 정책 지표 5종 + 이번 주 점수 변동.
 *
 * <p>원천은 {@link RoutineOutcome} 하나다. 이 테이블에는 <b>확정된 판정만</b> 쌓이므로
 * "확정된 건만 센다"가 조건 없이 성립하고, 이의 인용으로 뒤집힌 결과도 같은 행이 고쳐지므로
 * 통계·스트릭이 캘린더와 저절로 같은 값을 낸다(단일 원천 원칙).
 *
 * <p>사전 집계 테이블은 두지 않았다 — 현 규모에 과설계이고, 소급 정정이 잦은 도메인이라
 * 재집계가 오히려 복잡해진다(6. 이외 고려 사항). 느려지면 그때 전환한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeStatsService {

    /** 화면이 그리는 사이클 그리드 칸 수 — 정책이 정한 「최근 12주」다. */
    private static final int CYCLE_WEEKS = 12;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineOutcomeRepository outcomeRepo;
    private final ChallengeMemberRepository memberRepository;
    private final ScoreTransactionRepository transactionRepository;

    public MeStatsResponse stats(UUID userId) {
        List<RoutineOutcome> outcomes = outcomeRepo.findByUserId(userId);

        long success = 0, failed = 0;
        // 날짜 → [그날 성공 수, 그날 판정 수]. 스트릭은 하루 단위 판정이라 방을 가로질러 접는다.
        Map<LocalDate, int[]> byDay = new TreeMap<>();

        for (RoutineOutcome o : outcomes) {
            boolean ok = o.getStatus() == VerificationStatus.SUCCESS;
            if (!ok && o.getStatus() != VerificationStatus.FAILED) continue;   // 확정 종결만 센다
            if (ok) success++; else failed++;

            int[] day = byDay.computeIfAbsent(o.getTargetDate(), k -> new int[2]);
            day[1]++;
            if (ok) day[0]++;
        }

        return new MeStatsResponse(
                successRate(success, failed), success, streak(byDay),
                cycles12w(outcomes), completedCount(userId), weeklyScoreDelta(userId));
    }

    /**
     * 최근 12주 사이클 성과 — <b>언제나 12칸</b>이고 오래된 주가 앞이다.
     *
     * <p>판정이 없던 주를 빼지 않는 이유는 그리드 때문이다. 칸을 건너뛰면 클라이언트가 ISO 주차를
     * 직접 계산해 빈자리를 만들어야 하는데, 주차 계산은 연말·연초에 어긋나기 쉬워 값을 아는 쪽이
     * 채우는 편이 안전하다.
     *
     * <p>한 주의 판정을 전부 성공했으면 SUCCESS, 실패가 섞이면 PARTIAL, 전부 실패면 FAIL 이다.
     * 스트릭과 달리 <b>날짜가 아니라 주</b> 단위로 접으므로, 같은 주에 성공한 날과 실패한 날이
     * 함께 있으면 PARTIAL 이 된다.
     */
    private List<MeStatsResponse.Cycle> cycles12w(List<RoutineOutcome> outcomes) {
        LocalDate thisWeek = LocalDate.now(KST).with(DayOfWeek.MONDAY);
        LocalDate from = thisWeek.minusWeeks(CYCLE_WEEKS - 1L);

        // 주 시작일 → [성공 수, 판정 수]
        Map<LocalDate, int[]> byWeek = new HashMap<>();
        for (RoutineOutcome o : outcomes) {
            if (!isJudged(o)) continue;
            LocalDate week = o.getTargetDate().with(DayOfWeek.MONDAY);
            if (week.isBefore(from) || week.isAfter(thisWeek)) continue;
            int[] c = byWeek.computeIfAbsent(week, k -> new int[2]);
            c[1]++;
            if (o.getStatus() == VerificationStatus.SUCCESS) c[0]++;
        }

        List<MeStatsResponse.Cycle> cycles = new ArrayList<>(CYCLE_WEEKS);
        for (int i = 0; i < CYCLE_WEEKS; i++) {
            LocalDate week = from.plusWeeks(i);
            cycles.add(new MeStatsResponse.Cycle(isoWeek(week), weekResult(byWeek.get(week))));
        }
        return cycles;
    }

    private static boolean isJudged(RoutineOutcome o) {
        return o.getStatus() == VerificationStatus.SUCCESS || o.getStatus() == VerificationStatus.FAILED;
    }

    private static String weekResult(int[] counts) {
        if (counts == null || counts[1] == 0) return "NONE";
        if (counts[0] == counts[1]) return "SUCCESS";
        if (counts[0] == 0) return "FAIL";
        return "PARTIAL";
    }

    /** {@code 2026-W28} — 연말·연초가 어긋나지 않도록 ISO 주 기준 연도를 함께 쓴다. */
    private static String isoWeek(LocalDate mondayOfWeek) {
        return "%d-W%02d".formatted(
                mondayOfWeek.get(IsoFields.WEEK_BASED_YEAR),
                mondayOfWeek.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /**
     * 이번 주 점수 변동 — <b>계정 단위 합계이며 한도가 없다.</b>
     *
     * <p>정책 §4.7 의 ±20 은 <b>챌린지별 각 사이클</b> 한도지 계정 주간 한도가 아니다. 그 값을
     * 계정 주간에 붙이면 동시 참여 3개인 사용자의 실제 변동(±60까지)을 잘못 말하게 된다.
     * 화면에 「최대 ±20」 같은 문구를 붙이지 않는 것도 같은 이유다.
     *
     * <p>합산 대상은 <b>실제 반영분</b>({@code appliedDelta})이다. 한도나 0~2,000 경계에 걸려
     * 깎인 원점수를 더하면 화면의 변동폭과 실제 점수 변화가 어긋난다.
     */
    private long weeklyScoreDelta(UUID userId) {
        var weekStart = LocalDate.now(KST).with(DayOfWeek.MONDAY).atStartOfDay(KST).toInstant();
        return transactionRepository.findSince(userId, weekStart).stream()
                .mapToLong(ScoreTransaction::getAppliedDelta)
                .sum();
    }

    /**
     * 전체 성공률 — 방 랭킹과 동일 산식.
     *
     * <p><b>분모가 0이면 null 이다.</b> 비율을 만들 수 없는 상태를 0.0 으로 채우면 화면이
     * 「성공률 0%」를 그리는데, 그건 아직 아무 판정도 없는 사용자에게 전부 실패했다고 말하는
     * 것이다. 실제로 전부 실패한 계정(분모 있음)과 구분되지 않으면 두 사실이 같아져 버린다.
     */
    private Double successRate(long success, long failed) {
        long judged = success + failed;
        if (judged == 0) return null;
        return Math.round(1000.0 * success / judged) / 1000.0;
    }

    /**
     * 스트릭 — 그날 예정된 판정을 전부 성공한 날만 이어진다.
     *
     * <p><b>판정이 없는 날은 건너뛴다.</b> 달력의 연속이 아니라 "판정이 있었던 날의 연속"이라서,
     * 주 3회 루틴의 쉬는 날이 스트릭을 죽이지 않는다. 그래서 날짜 간격을 보지 않고 판정이 있는
     * 날만 순서대로 훑는다.
     *
     * <p>현재 스트릭은 <b>가장 최근 판정일부터</b> 거슬러 센다. 유예 구간(확정 전 2일)의 실패 예정은
     * 아직 RoutineOutcome 에 없으므로 자연히 "아직 끊기지 않은 것"으로 취급된다.
     */
    private MeStatsResponse.Streak streak(Map<LocalDate, int[]> byDay) {
        int best = 0, run = 0, current = 0;
        boolean stillCurrent = true;

        List<LocalDate> days = List.copyOf(byDay.keySet());   // TreeMap 이라 오래된 순
        for (LocalDate d : days) {
            int[] c = byDay.get(d);
            if (c[0] == c[1]) { run++; best = Math.max(best, run); }
            else run = 0;
        }
        // 현재 스트릭은 최신 쪽에서 거슬러 올라간다.
        for (int i = days.size() - 1; i >= 0 && stillCurrent; i--) {
            int[] c = byDay.get(days.get(i));
            if (c[0] == c[1]) current++; else stillCurrent = false;
        }
        return new MeStatsResponse.Streak(current, best);
    }

    /** 완주 개수 — 완주 = 기간 중 80% 이상 성공(챌린지 탐색 정책과 같은 커트라인). */
    private long completedCount(UUID userId) {
        return memberRepository.findByUserId(userId).stream()
                .map(ChallengeMember::getProgressRate)
                .filter(CompletionPolicy::isCompleted)
                .count();
    }
}
