package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.HealthMetric;
import com.ruleup.ruleup_backend.verification.signal.HealthReading;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;

import java.time.Instant;
import java.util.*;

/**
 * 시간 창 안의 걸음 — 기상(창 안에서 걸었는가)과 취침(창 안에서 걷지 않았는가)이 같은 셈을 쓴다.
 * 구간(startTime~endTime)이 있는 STEPS 기록만, 창에 겹친 비율만큼, 겹친 기록은 한 번만 센다.
 */
final class StepCounter {

    private StepCounter() {}

    /** 창 안 걸음 합 — 출처 게이트를 통과한 구간 기록만, 창에 겹친 비율만큼, 겹친 기록은 한 번만. */
    static double inWindow(List<SyncSignal> signals, Window window, List<String> trustedOrigins, Set<String> rejected,
                           boolean[] rejectedInWindow) {
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

                String gate = HealthEvaluator.gateReason(r, true, trustedOrigins);
                if (gate != null) {
                    String origin = (r.origin() != null && r.origin().dataOrigin() != null) ? r.origin().dataOrigin() : "?";
                    rejected.add(origin + ":" + gate);
                    if (inWindow && r.value() != null && r.value().signum() > 0) rejectedInWindow[0] = true;
                    continue;
                }
                if (!inWindow || r.value() == null) continue;                          // 구간 없음·창 밖 기록
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
