package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.domain.WakeConfig;
import com.ruleup.ruleup_backend.verification.signal.ScreenEvent;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WAKE(기상) 평가기 — 도달형(§2.6, §2.11).
 *  - 신호: SCREEN_TIME.screenEvents(UNLOCK/SCREEN_ON) 중 당일 첫 발생 = firstUnlockAt.
 *  - 창: [하루 시작, beforeTime]. beforeTime에 창이 닫힌다(자정 아님).
 *  - 판정: firstUnlockAt ≤ beforeTime → SUCCESS / 창 닫힘·미발생 → FAILED(WOKE_UP_LATE) / 그 외 → PENDING.
 *  - 신호가 델타라 priorEvidence.firstUnlockAt과 이번 신호의 최솟값을 누적(멱등·증분).
 *  - firstUnlockAt은 windowAnchor(base=WAKE) 종속 창의 앵커로도 outcome에 실어 보낸다.
 */
@Component
public class WakeEvaluator implements MethodEvaluator {

    @Override
    public VerificationMethod method() { return VerificationMethod.WAKE; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        WakeConfig cfg = ctx.config().wake();
        if (cfg == null || cfg.beforeTime() == null) {
            return EvaluationOutcome.pending(null, null);   // 잘못된 config는 무시(PENDING 유지)
        }

        Instant windowCloses = TimeWindows.atTime(ctx.targetDate(), cfg.beforeTime(), ctx.zone());
        Instant dayStart = TimeWindows.startOfDay(ctx.targetDate(), ctx.zone());
        Window window = new Window(dayStart, windowCloses);

        // prior + 이번 신호 중 가장 이른 잠금해제(창 내)
        Instant firstUnlock = min(priorFirstUnlock(ctx.priorEvidence()), earliestUnlockInWindow(ctx.signals(), window));

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("beforeTime", cfg.beforeTime());
        if (firstUnlock != null) evidence.put("firstUnlockAt", firstUnlock.toString());

        EvaluationOutcome outcome;
        if (firstUnlock != null) {
            outcome = EvaluationOutcome.success(evidence, windowCloses);        // 창 내 기상 → 즉시 성공
        } else if (window.isClosed(ctx.now())) {
            outcome = EvaluationOutcome.failed("WOKE_UP_LATE", evidence, windowCloses);  // 창 닫힘·미발생
        } else {
            outcome = EvaluationOutcome.pending(evidence, windowCloses);        // 아직 대기
        }
        return outcome.withFirstUnlockAt(firstUnlock);
    }

    private Instant priorFirstUnlock(Map<String, Object> prior) {
        if (prior == null) return null;
        Object v = prior.get("firstUnlockAt");
        return (v != null) ? TimeWindows.parseInstant(v.toString()) : null;
    }

    private Instant earliestUnlockInWindow(List<SyncSignal> signals, Window window) {
        if (signals == null) return null;
        Instant earliest = null;
        for (SyncSignal s : signals) {
            if (!SignalType.SCREEN_TIME.name().equals(s.type()) || s.screenEvents() == null) continue;
            for (ScreenEvent e : s.screenEvents()) {
                Instant at = TimeWindows.parseInstant(e.at());
                if (at == null || !window.contains(at)) continue;
                if (earliest == null || at.isBefore(earliest)) earliest = at;
            }
        }
        return earliest;
    }

    private Instant min(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }
}
