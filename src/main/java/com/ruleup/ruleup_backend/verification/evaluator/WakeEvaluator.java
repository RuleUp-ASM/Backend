package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.domain.VerificationStatus;
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
 *  - firstUnlockAt은 windowAnchor(base=WAKE) 종속 창의 앵커로도 쓰인다(outcome에 실어 보냄).
 */
@Component
public class WakeEvaluator implements MethodEvaluator {

    @Override
    public VerificationMethod method() { return VerificationMethod.WAKE; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        WakeConfig cfg = ctx.config().wake();
        if (cfg == null || cfg.beforeTime() == null) {
            // 설정 없음 → 평가 불가, PENDING 유지(잘못된 config는 무시).
            return EvaluationOutcome.pending(null, null);
        }

        Instant windowCloses = TimeWindows.atTime(ctx.targetDate(), cfg.beforeTime(), ctx.zone());
        Instant dayStart = TimeWindows.startOfDay(ctx.targetDate(), ctx.zone());
        Window window = new Window(dayStart, windowCloses);

        Instant firstUnlock = firstUnlockAt(ctx.signals(), window);

        Map<String, Object> evidence = new HashMap<>();
        if (firstUnlock != null) evidence.put("firstUnlockAt", firstUnlock.toString());
        evidence.put("beforeTime", cfg.beforeTime());

        EvaluationOutcome outcome;
        if (firstUnlock != null) {
            // 창 안에서 잠금해제 발생 → 기상 성공(즉시 잠금)
            outcome = EvaluationOutcome.success(evidence, windowCloses);
        } else if (window.isClosed(ctx.now())) {
            // 창 닫혔는데 잠금해제 없음 → 기상 실패
            outcome = EvaluationOutcome.failed("WOKE_UP_LATE", evidence, windowCloses);
        } else {
            // 아직 창 안, 미발생 → 대기
            outcome = EvaluationOutcome.pending(evidence, windowCloses);
        }
        return outcome.withFirstUnlockAt(firstUnlock);
    }

    /** 창 내(=하루 시작 ~ beforeTime) screenEvents 중 가장 이른 시각. 없으면 null. */
    private Instant firstUnlockAt(List<SyncSignal> signals, Window window) {
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
}
