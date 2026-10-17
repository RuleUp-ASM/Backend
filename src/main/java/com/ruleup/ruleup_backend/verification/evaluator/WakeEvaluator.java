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
 *  - 창: [하루 시작, beforeTime + graceMinutes]. 창에 걸친 기록은 겹친 비율만큼만 센다.
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
        Instant windowCloses = target.plusSeconds(60L * Math.max(cfg.graceMinutes(), 0));
        Window window = new Window(TimeWindows.startOfDay(ctx.targetDate(), ctx.zone()), windowCloses);

        Set<String> rejected = new LinkedHashSet<>();
        double steps = stepsInWindow(ctx.signals(), window, cfg, rejected);

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("beforeTime", cfg.beforeTime());
        evidence.put("graceMinutes", cfg.graceMinutes());
        evidence.put("deadline", LocalTime.ofInstant(windowCloses, ctx.zone()).format(HHMM));
        evidence.put("metric", HealthMetric.STEPS.name());
        evidence.put("unit", "count");
        evidence.put("value", Math.round(steps));
        evidence.put("goal", cfg.minSteps());
        if (!rejected.isEmpty()) evidence.put("rejectedOrigins", new ArrayList<>(rejected));

        if (cfg.minSteps() > 0 && steps >= cfg.minSteps()) {
            return EvaluationOutcome.success(evidence, windowCloses);          // 창 안에서 움직였다 → 즉시 성공
        }
        if (window.isClosed(ctx.now())) {
            return EvaluationOutcome.violated("WOKE_UP_LATE", evidence, windowCloses);
        }
        return EvaluationOutcome.pending(evidence, windowCloses);
    }

    /** 창 안 걸음 합 — 출처 게이트를 통과한 구간 기록만, 창에 겹친 비율만큼, 겹친 기록은 한 번만. */
    private double stepsInWindow(List<SyncSignal> signals, Window window, WakeConfig cfg, Set<String> rejected) {
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
                String gate = HealthEvaluator.gateReason(r, true, cfg.trustedOrigins());
                if (gate != null) {
                    String origin = (r.origin() != null && r.origin().dataOrigin() != null) ? r.origin().dataOrigin() : "?";
                    rejected.add(origin + ":" + gate);
                    continue;
                }
                Instant from = TimeWindows.parseInstant(r.startTime());
                Instant to = TimeWindows.parseInstant(r.endTime());
                if (from == null || to == null || !to.isAfter(from) || r.value() == null) continue;

                Instant start = from.isBefore(window.start()) ? window.start() : from;
                Instant end = to.isAfter(window.end()) ? window.end() : to;
                if (!end.isAfter(start)) continue;                                     // 창 밖 기록
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
