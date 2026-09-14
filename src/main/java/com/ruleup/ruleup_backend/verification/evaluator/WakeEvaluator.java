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
 *  - 그날 원본 전부에서 가장 이른 잠금해제를 매번 다시 고른다 — 이월 상태가 없어 도착 순서와 무관하다.
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

        // 그날 원본 중 창 안에서 가장 이른 잠금해제
        Instant firstUnlock = earliestUnlockInWindow(ctx.signals(), window);

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("beforeTime", cfg.beforeTime());
        if (firstUnlock != null) evidence.put("firstUnlockAt", firstUnlock.toString());

        EvaluationOutcome outcome;
        if (firstUnlock != null) {
            outcome = EvaluationOutcome.success(evidence, windowCloses);        // 창 내 기상 → 즉시 성공
        } else if (window.isClosed(ctx.now())) {
            outcome = EvaluationOutcome.violated("WOKE_UP_LATE", evidence, windowCloses);  // 창 닫힘·미발생
        } else {
            outcome = EvaluationOutcome.pending(evidence, windowCloses);        // 아직 대기
        }
        return outcome;
    }

    private Instant earliestUnlockInWindow(List<SyncSignal> signals, Window window) {
        if (signals == null) return null;
        Instant earliest = null;
        for (SyncSignal s : signals) {
            if (!SignalType.SCREEN_TIME.name().equals(s.type()) || s.screenEvents() == null) continue;
            for (ScreenEvent e : s.screenEvents()) {
                // 기상 판정은 "당일 첫 잠금 해제"다(인증 정책 §1.1). 화면만 켜지는 일은 알림 확인으로도
                // 흔해서, SCREEN_ON 을 기상으로 세면 자는 사람이 깬 것으로 잡힌다.
                if (!"UNLOCK".equalsIgnoreCase(e.event())) continue;
                Instant at = TimeWindows.parseInstant(e.at());
                if (at == null || !window.contains(at)) continue;
                if (earliest == null || at.isBefore(earliest)) earliest = at;
            }
        }
        return earliest;
    }
}
