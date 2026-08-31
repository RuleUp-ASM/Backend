package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.score.domain.ChallengeStreak;
import com.ruleup.ruleup_backend.score.domain.CycleLimit;
import com.ruleup.ruleup_backend.score.domain.CycleResult;
import com.ruleup.ruleup_backend.score.domain.CycleScoreState;
import com.ruleup.ruleup_backend.score.domain.IncidentType;
import com.ruleup.ruleup_backend.score.domain.ScoreCorrection;
import com.ruleup.ruleup_backend.score.domain.ScoreLedgerReason;
import com.ruleup.ruleup_backend.score.domain.ScoreTransaction;
import com.ruleup.ruleup_backend.score.domain.TierPoints;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.verification.domain.VerifiedVia;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 티어·점수 엔진 — 점수 및 티어 정책 §4, 티어·점수 백엔드 테크 스펙.
 *
 * <h2>왜 이벤트가 아니라 재정산인가</h2>
 * 정책은 "성공 확정 즉시 반영"을 요구하지만, 성공에는 별도 도메인 이벤트가 없고 확정 경로가
 * 여러 곳(sync 즉시 · 확정 배치 · 수동 인증)이다. 그래서 이벤트를 하나하나 잡는 대신
 * <b>판정 원본에서 카운트를 다시 세고 차이만 반영</b>한다. 정책 §4.5 가 이걸 허락한다 —
 * "반영 누계가 카운트만의 함수이므로 재계산·소급 정정 시 카운트를 고치고 차이만 반영하면 되고,
 * 같은 이벤트를 중복 처리해도 결과가 달라지지 않는다."
 *
 * <p>이 선택의 대가는 이벤트 하나가 아니라 사이클 하나를 읽는다는 것이고(7행), 얻는 것은
 * <b>누락도 중복도 구조적으로 불가능</b>하다는 것이다. 이의 인용 정정도 같은 함수를 다시
 * 부르는 것으로 끝난다 — 역분개 원장을 따로 짜지 않는다.
 *
 * <h2>직렬화</h2>
 * 같은 사용자의 점수 쓰기는 전부 {@code user_score_summaries} 행 잠금 뒤에서 돈다. 그 다음
 * 사이클 행을 잠근다. 잠금 순서를 계정 → 사이클로 전 경로에 고정해 교착을 피한다.
 */
@Service
@RequiredArgsConstructor
public class ScoreService {

    private static final Logger log = LoggerFactory.getLogger(ScoreService.class);

    /**
     * 점수 계산에서 통째로 빠지는 인증 방식 — 수동 인증(정책 §4.1).
     *
     * <p>"성공 누적·확정 미달 계산에 넣지 않는다"는 문구가 핵심이다. 수동으로 채운 날을 <b>성공만</b>
     * 빼고 인증 가능일에서는 소모된 것으로 세면, 만회할 날이 줄어 미달이 확정된다 — 수동 인증이
     * 감점으로 둔갑한다. 그래서 그 날 자체를 없는 것처럼 건너뛴다. 수동 인증 챌린지는 결과적으로
     * 티어 점수가 전혀 움직이지 않고, 통계·랭킹에는 그대로 집계된다.
     *
     * <p>이의로 정정된 성공(APPEAL)은 제외 대상이 아니다 — 정상 성공과 동일하게 복원한다(§4.10).
     */
    private static final VerifiedVia UNSCORED_VIA = VerifiedVia.MANUAL;

    private final UserScoreSummaryRepository summaryRepository;
    private final CycleScoreStateRepository cycleRepository;
    private final ChallengeStreakRepository streakRepository;
    private final ScoreTransactionRepository ledgerRepository;
    private final ScoreCorrectionRepository correctionRepository;
    private final ChallengeRepository challengeRepository;
    private final VerificationDailyRepository dailyRepository;

    // ===== 날짜별 판정 반영 =====

    /**
     * 한 사이클의 점수를 판정 원본에 맞춰 다시 정산한다. <b>멱등</b> — 몇 번을 돌려도 같은 상태에 수렴한다.
     *
     * <p>성공 축과 미달 축은 서로 <b>분리된 정수 카운트</b>다. 미달은 실패 건수가 아니라
     * {@code max(0, 목표 − 성공 − 남은 인증 가능일)} 이라, 주 N회 유연 루틴에서는 남은 날로 만회할 수
     * 있는 동안 차감하지 않다가 수학적으로 만회가 불가능해지는 순간 확정된다.
     */
    @Transactional
    public void reconcileCycle(UUID userId, UUID challengeId, int cycleNo) {
        Challenge challenge = challengeRepository.findById(challengeId).orElse(null);
        if (challenge == null) return;

        UserScoreSummary summary = lockSummary(userId);
        CycleScoreState cycle = openCycle(summary, challenge, cycleNo);

        Counts counts = countFromJudgements(userId, challenge, cycle);
        cycle.advanceWatermark(counts.lastJudgedAt());
        int rawDelta = cycle.recount(counts.success(), counts.miss());
        if (rawDelta == 0) return;   // 카운트가 그대로면 반영할 것도 없다

        ScoreLedgerReason reason = rawDelta > 0 ? ScoreLedgerReason.DAILY_SUCCESS
                                                : ScoreLedgerReason.CONFIRMED_MISS;
        applyRoutine(summary, cycle, rawDelta, reason,
                "cycle:%s:%d:v%d-%d".formatted(challengeId, cycleNo, counts.success(), counts.miss()));
    }

    // ===== 사이클 마감 =====

    /**
     * 사이클 마감 — 3단계 판정과 연속 기록 갱신, 그리고 보너스·추가 감점.
     *
     * <p>정책 §4.5 의 {@code f(N) = W} 덕분에 <b>마감 보정이 없다.</b> 주간 목표를 다 채웠으면 누계가
     * 이미 주간 총 배점과 정확히 같아서 정산할 잔여 오차 자체가 생기지 않는다.
     *
     * <p>보너스·추가 감점도 같은 사이클의 순변동 한도를 거친다. 이미 ±20 에 닿아 있으면 보너스가
     * 0으로 잘릴 수 있고, 이는 정책 §4.7 의 의도된 동작이다.
     */
    @Transactional
    public void closeCycle(UUID userId, UUID challengeId, int cycleNo) {
        UserScoreSummary summary = lockSummary(userId);
        CycleScoreState cycle = cycleRepository.findForUpdate(userId, challengeId, cycleNo).orElse(null);
        if (cycle == null || cycle.isClosed()) return;   // 마감 멱등

        CycleResult result = CycleResult.of(cycle.getSuccessCount(), cycle.getTargetCount());
        ChallengeStreak streak = streakRepository
                .findById(new ChallengeStreak.Key(userId, challengeId))
                .orElseGet(() -> streakRepository.save(ChallengeStreak.start(userId, challengeId)));

        if (!streak.alreadyApplied(cycleNo)) {
            streak.apply(result, cycleNo);
            int streakDelta = streakDelta(cycle, result, streak);
            if (streakDelta != 0) {
                ScoreLedgerReason reason = streakDelta > 0 ? ScoreLedgerReason.STREAK_BONUS
                                                           : ScoreLedgerReason.STREAK_PENALTY;
                applyRoutine(summary, cycle, streakDelta, reason,
                        "streak:%s:%d".formatted(challengeId, cycleNo));
            }
        }
        cycle.close(result, Instant.now());
    }

    /** 연속 성공 보너스 또는 연속 실패 추가 감점. 부분 달성은 추가 점수가 없다. */
    private int streakDelta(CycleScoreState cycle, CycleResult result, ChallengeStreak streak) {
        return switch (result) {
            case SUCCESS -> TierPoints.streakBonus(cycle.getTierSnapshot(), streak.getSuccessStreak());
            case FAILURE -> TierPoints.failurePenalty(streak.getFailureStreak());
            case PARTIAL -> 0;
        };
    }

    // ===== 사건성 감점 =====

    /**
     * 사건성 감점 — <b>사이클 순변동 한도를 거치지 않고</b> 즉시 전액 반영한다.
     *
     * <p>사이클 상태를 조회하지도 변경하지도 않는다. 한도는 "한 주에 얼마나 움직일 수 있나"를 다루는
     * 장치인데 부정행위는 주간 성과가 아니기 때문이다. 연속 기록도 건드리지 않는다.
     *
     * @param sourceId      사건 원본 식별자 — 같은 사건을 두 번 전달해도 한 번만 반영한다
     * @param progressWeeks 중도 탈퇴 면제 판정용 진행 주간 수. 다른 사건에는 쓰이지 않는다
     */
    @Transactional
    public void applyIncident(UUID userId, UUID challengeId, IncidentType type,
                              String sourceId, int progressWeeks) {
        String key = "incident:%s:%s".formatted(type, sourceId);
        if (ledgerRepository.existsByIdempotencyKey(key)) return;

        int deduction = type.deduction(progressWeeks);
        if (deduction == 0) return;   // 중도 탈퇴 면제 등 — 이벤트를 만들지 않는다

        UserScoreSummary summary = lockSummary(userId);
        // rawCumulative·limitedCumulative 를 0으로 넣어 사이클 상태를 건드리지 않고 계정 범위만 적용한다.
        CycleLimit.Result result = applyAccountRangeOnly(deduction, summary.getTotalScore());

        summary.applyScore(result.scoreAfter());
        ledgerRepository.save(ScoreTransaction.incident(userId, challengeId, type, result, key));
    }

    /**
     * 사이클 한도를 건너뛰고 누적 점수 0~2,000 범위만 적용한다.
     * {@link CycleLimit#apply} 를 그대로 쓰면 ±20 으로 잘리므로 여기서만 따로 계산한다.
     */
    private CycleLimit.Result applyAccountRangeOnly(int rawDelta, long scoreBefore) {
        long scoreAfter = Math.max(0, Math.min(com.ruleup.ruleup_backend.score.domain.TierBands.MAX_SCORE,
                scoreBefore + rawDelta));
        int applied = (int) (scoreAfter - scoreBefore);
        return new CycleLimit.Result(rawDelta, rawDelta, applied, 0, 0, scoreAfter);
    }

    // ===== 소급 정정 =====

    /**
     * 이의 인용에 따른 소급 정정 — 정책 §4.10.
     *
     * <p>역분개 한 건으로 끝내지 않는다. 사이클 카운트를 원본에서 <b>처음부터 다시 세고</b>
     * 반영 누계를 재산출한 뒤 차이를 반영한다. 반영 누계가 카운트만의 함수라, 이 재계산이 곧
     * "취소된 미달 확정분의 전액 복원"이다 — 비율 감액 같은 부분 복원이 애초에 생기지 않는다.
     *
     * <p>사이클 순변동 한도도 함께 다시 적용된다. 과거에 한도로 잘렸던 이벤트의 실제 반영량이
     * 달라질 수 있는데, 원점수 누계를 다시 세우므로 그것도 자동으로 따라온다.
     *
     * <p>정정 전 원장은 지우지 않는다. 관계만 {@code score_corrections} 에 남긴다.
     */
    @Transactional
    public void recompute(UUID userId, UUID challengeId, int cycleNo, UUID originalEventId) {
        if (correctionRepository.existsByOriginalEventIdAndCorrectionVersion(originalEventId, 1)) return;

        Challenge challenge = challengeRepository.findById(challengeId).orElse(null);
        if (challenge == null) return;

        UserScoreSummary summary = lockSummary(userId);
        CycleScoreState cycle = cycleRepository.findForUpdate(userId, challengeId, cycleNo).orElse(null);
        if (cycle == null) return;

        // 원점수 누계를 0에서 다시 세운다 — 한도 재적용까지 한 번에 따라온다.
        int rawBefore = cycle.getRawCumulative();
        cycle.resetForRecompute();
        Counts counts = countFromJudgements(userId, challenge, cycle);
        int rebuilt = cycle.recount(counts.success(), counts.miss());
        int rawDelta = rebuilt - rawBefore;

        correctionRepository.save(ScoreCorrection.of(userId, originalEventId, challengeId, cycleNo,
                Instant.now()));

        if (rawDelta == 0) return;
        CycleLimit.Result result = CycleLimit.apply(rawDelta, rawBefore, cycle.getLimitedCumulative(),
                summary.getTotalScore());
        cycle.applyLimit(result);
        summary.applyScore(result.scoreAfter());
        ledgerRepository.save(ScoreTransaction.reversal(userId, challengeId, cycleNo, result,
                "correction:%s:1".formatted(originalEventId)));
        log.info("점수 소급 정정: user={} challenge={} cycle={} raw={}", userId, challengeId, cycleNo, rawDelta);
    }

    // ===== 공통 =====

    /** 루틴 점수 반영 — 사이클 한도를 거쳐 원장·사이클 상태·계정 상태를 한 트랜잭션에서 갱신한다. */
    private void applyRoutine(UserScoreSummary summary, CycleScoreState cycle, int rawDelta,
                              ScoreLedgerReason reason, String idempotencyKey) {
        if (ledgerRepository.existsByIdempotencyKey(idempotencyKey)) return;

        CycleLimit.Result result = CycleLimit.apply(rawDelta, cycle.getRawCumulative(),
                cycle.getLimitedCumulative(), summary.getTotalScore());
        cycle.applyLimit(result);
        summary.applyScore(result.scoreAfter());
        ledgerRepository.save(ScoreTransaction.routine(summary.getUserId(), cycle.getChallengeId(),
                cycle.getCycleNo(), reason, result, idempotencyKey));
    }

    private UserScoreSummary lockSummary(UUID userId) {
        return summaryRepository.findForUpdate(userId)
                .orElseGet(() -> summaryRepository.save(UserScoreSummary.initialize(userId)));
    }

    /**
     * 사이클 상태를 열거나 가져온다. 배점 티어는 <b>여는 시점의 실제 티어</b>로 고정되고 이후 바뀌지 않는다 —
     * 주중 승급·강등이 그 사이클의 배점·판정·보너스를 흔들면 한 주의 총 배점을 설명할 수 없게 된다.
     */
    private CycleScoreState openCycle(UserScoreSummary summary, Challenge challenge, int cycleNo) {
        return cycleRepository.findForUpdate(summary.getUserId(), challenge.getId(), cycleNo)
                .orElseGet(() -> cycleRepository.save(CycleScoreState.open(
                        summary.getUserId(), challenge.getId(), cycleNo,
                        summary.getActualTier(), targetCount(challenge), cycleStart(challenge, cycleNo))));
    }

    private int targetCount(Challenge challenge) {
        Integer weekly = challenge.getWeeklyCount();
        return (weekly == null || weekly < 1) ? 7 : Math.min(7, weekly);
    }

    private LocalDate cycleStart(Challenge challenge, int cycleNo) {
        return challenge.getStartDate().plusDays((long) (cycleNo - 1) * ChallengeCycle.CYCLE_DAYS);
    }

    /**
     * 판정 원본에서 성공·미달 카운트를 다시 센다.
     *
     * <p>미달은 실패 건수가 아니다. {@code max(0, 목표 − 성공 − 남은 인증 가능일)} 이므로,
     * 주 5회 루틴에서 이틀 실패해도 남은 5일로 만회할 수 있으면 0이다. 남은 인증 가능일은
     * 사이클 7일 중 <b>아직 확정되지 않은</b> 날의 수다 — 확정된 날은 더 이상 성공으로 바뀌지 않는다.
     */
    private Counts countFromJudgements(UUID userId, Challenge challenge, CycleScoreState cycle) {
        LocalDate from = cycle.getStartedOn();
        LocalDate to = from.plusDays(ChallengeCycle.CYCLE_DAYS - 1L);
        List<VerificationDaily> dailies = dailyRepository
                .findByUserIdAndChallengeIdAndTargetDateBetween(userId, challenge.getId(), from, to);

        int success = 0, judged = 0;
        Instant lastJudgedAt = null;
        for (VerificationDaily d : dailies) {
            if (!d.getStatus().isTerminal()) continue;          // 확정 전이면 만회할 기회가 남아 있다
            if (d.getVerifiedVia() == UNSCORED_VIA) continue;   // 수동 인증은 두 축 어디에도 넣지 않는다
            judged++;
            if (d.getStatus() == VerificationStatus.SUCCESS) success++;
            if (d.getVerifiedAt() != null
                    && (lastJudgedAt == null || d.getVerifiedAt().isAfter(lastJudgedAt)))
                lastJudgedAt = d.getVerifiedAt();
        }

        int target = cycle.getTargetCount();
        int remaining = Math.max(0, ChallengeCycle.CYCLE_DAYS - judged);
        int miss = Math.max(0, Math.min(target, target - success - remaining));
        return new Counts(Math.min(target, success), miss, lastJudgedAt);
    }

    /** 성공 축과 미달 축은 서로 분리된 정수 카운트다. */
    private record Counts(int success, int miss, Instant lastJudgedAt) {}
}
