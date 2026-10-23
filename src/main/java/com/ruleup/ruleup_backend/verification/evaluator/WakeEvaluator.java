package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.HealthMetric;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.domain.WakeConfig;
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
        double steps = StepCounter.inWindow(ctx.signals(), window, cfg.trustedOrigins(), rejected, rejectedInWindow);

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
}
