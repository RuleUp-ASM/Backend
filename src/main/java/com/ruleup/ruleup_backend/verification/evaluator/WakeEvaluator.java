package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.HealthMetric;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.domain.WakeConfig;
import com.ruleup.ruleup_backend.verification.signal.HealthReading;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * WAKE(기상) 평가기 — 도달형(§2.6, §2.11). <b>일어나서 걸었는가</b>로 본다.
 *  - 신호: HEALTH 의 STEPS 기록(Health Connect StepsRecord). 구간(startTime~endTime)이 있는 기록만 쓴다 —
 *    「언제 걸었는지」가 판정의 전부라, 구간 없는 하루 누적값은 근거가 되지 못한다.
 *  - 창: [beforeTime - leadMinutes, beforeTime + graceMinutes] — 07:00 목표면 06:00~07:30.
 *    하루 시작(00:00)부터 보면 새벽에 잠깐 깨서 걸은 걸음이 기상이 된다. 창에 걸친 기록은 겹친 비율만큼만 센다.
 *  - 창보다 긴 기록은 세지 않는다. 삼성헬스는 하루 걸음을 00:00~23:59 기록 하나로 쓰는데, 비율로 나누면
 *    11시에 걸은 걸음도 창 몫(1.5/24)이 생겨 매일 성공했다(2026-10-25~28 prod). 창보다 긴 구간은 걸음이
 *    창 안에서 났는지 말해 주지 못한다.
 *  - 판정: 창 안 걸음 ≥ minSteps → SUCCESS / 창 닫힘·미달 → FAILED(WOKE_UP_LATE) / 그 외 → PENDING.
 *  - 출처 게이트는 움직임(HEALTH)과 같다 — 손입력·신뢰 밖 출처는 뺀다. 폰과 워치가 같은 걸음을 각자
 *    기록하면 겹친 구간은 한 번만 센다.
 *  - 그날 원본 전부를 매번 다시 읽는다 — 이월 상태가 없어 도착 순서와 무관하다.
 */
@Component
public class WakeEvaluator implements MethodEvaluator {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public VerificationMethod method() { return VerificationMethod.WAKE; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        WakeConfig cfg = ctx.config().wake();
        if (cfg == null || cfg.beforeTime() == null) {
            return EvaluationOutcome.pending(null, null);   // 잘못된 config는 무시(PENDING 유지)
        }

        Instant target = TimeWindows.atTime(ctx.targetDate(), cfg.beforeTime(), ctx.zone());
        Instant windowOpens = target.minusSeconds(60L * Math.max(cfg.leadMinutes(), 0));
        Instant windowCloses = target.plusSeconds(60L * Math.max(cfg.graceMinutes(), 0));
        Window window = new Window(windowOpens, windowCloses);

        Set<String> rejected = new LinkedHashSet<>();
        boolean[] rejectedInWindow = {false};
        int[] coarseSkipped = {0};
        double steps = stepsInWindow(ctx.signals(), window, cfg, rejected, rejectedInWindow, coarseSkipped);

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("beforeTime", cfg.beforeTime());
        evidence.put("leadMinutes", cfg.leadMinutes());
        evidence.put("graceMinutes", cfg.graceMinutes());
        evidence.put("windowStart", LocalTime.ofInstant(windowOpens, ctx.zone()).format(HHMM));
        evidence.put("deadline", LocalTime.ofInstant(windowCloses, ctx.zone()).format(HHMM));
        evidence.put("metric", HealthMetric.STEPS.name());
        evidence.put("unit", "count");
        evidence.put("value", Math.round(steps));
        evidence.put("goal", cfg.minSteps());
        if (!rejected.isEmpty()) evidence.put("rejectedOrigins", new ArrayList<>(rejected));
        if (coarseSkipped[0] > 0) evidence.put("coarseRecordsSkipped", coarseSkipped[0]);

        if (cfg.minSteps() > 0 && steps >= cfg.minSteps()) {
            return EvaluationOutcome.success(evidence, windowCloses);          // 창 안에서 움직였다 → 즉시 성공
        }
        if (window.isClosed(ctx.now())) {
            // 창 안에 걸음 기록은 있었는데 출처·손입력으로 전부 빠졌다면 늦잠이 아니다 — 늦잠이라고 하면
            // 일찍 일어나 걸은 사람이 무엇을 고쳐야 할지 모른다. 걸음 목표 판정과 같은 사유를 쓴다.
            String reason = (steps <= 0 && rejectedInWindow[0]) ? "UNTRUSTED_HEALTH_SOURCE" : "WOKE_UP_LATE";
            return EvaluationOutcome.violated(reason, evidence, windowCloses);
        }
        return EvaluationOutcome.pending(evidence, windowCloses);
    }

    /** 창 안 걸음 합 — 출처 게이트를 통과한 창보다 짧은 구간 기록만, 창에 겹친 비율만큼, 겹친 기록은 한 번만. */
    private double stepsInWindow(List<SyncSignal> signals, Window window, WakeConfig cfg, Set<String> rejected,
                                 boolean[] rejectedInWindow, int[] coarseSkipped) {
        long windowSec = window.end().getEpochSecond() - window.start().getEpochSecond();
        if (signals == null) return 0;
        // 같은 기록은 <b>마지막에 받은 것</b>으로 센다. 앱은 sync 마다 그날 기록을 다시 보내고, 기록 앱은 같은 기록의
        // 걸음 수·끝 시각을 키워 가며 고친다 — 처음 받은 값(예: 3걸음)에 묶이면 그날은 끝내 문턱을 못 넘는다.
        // 신호는 받은 순서(행 id = 시간순)로 오므로 뒤에 온 것이 덮는다.
        Map<String, HealthEvaluator.Measured> byRecord = new LinkedHashMap<>();
        List<HealthEvaluator.Measured> clipped = new ArrayList<>();
        for (SyncSignal s : signals) {
            if (!SignalType.HEALTH.name().equals(s.type()) || s.readings() == null) continue;
            for (HealthReading r : s.readings()) {
                if (r == null || !HealthMetric.STEPS.name().equalsIgnoreCase(r.metric())) continue;
                Instant from = TimeWindows.parseInstant(r.startTime());
                Instant to = TimeWindows.parseInstant(r.endTime());
                boolean timed = from != null && to != null && to.isAfter(from);
                Instant start = timed && from.isBefore(window.start()) ? window.start() : from;
                Instant end = timed && to.isAfter(window.end()) ? window.end() : to;
                boolean inWindow = timed && end.isAfter(start);

                String gate = HealthEvaluator.gateReason(r, true, cfg.trustedOrigins());
                if (gate != null) {
                    String origin = (r.origin() != null && r.origin().dataOrigin() != null) ? r.origin().dataOrigin() : "?";
                    rejected.add(origin + ":" + gate);
                    if (inWindow && r.value() != null && r.value().signum() > 0) rejectedInWindow[0] = true;
                    continue;
                }
                if (!inWindow || r.value() == null) continue;                          // 구간 없음·창 밖 기록
                if (to.getEpochSecond() - from.getEpochSecond() > windowSec) {         // 하루 합계 같은 굵은 기록
                    coarseSkipped[0]++;
                    continue;
                }
                double share = (double) (end.getEpochSecond() - start.getEpochSecond())
                        / (to.getEpochSecond() - from.getEpochSecond());
                HealthEvaluator.Measured m = new HealthEvaluator.Measured(start, end, r.value().doubleValue() * share);
                if (r.recordId() != null) byRecord.put(r.recordId(), m);
                else clipped.add(m);
            }
        }
        clipped.addAll(byRecord.values());
        return HealthEvaluator.dedupedSum(clipped);
    }
}
