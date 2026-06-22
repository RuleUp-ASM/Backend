package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.GpsConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.signal.GeoPoint;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * GPS DISTANCE 평가기 (§2.16, 부록 4.3) — 도달형. RUNNING_SESSION 경로 누적거리 ≥ goalKm.
 *  - 세션별 points를 정제(정확도 컷·점프 컷) → haversine 누적.
 *  - 하루 여러 세션 합산. sessionStart 멱등(중복 전송 무시). 상태는 evidence에 누적.
 */
@Component
public class GpsDistanceEvaluator implements MethodEvaluator {

    @Override
    public VerificationMethod method() { return VerificationMethod.GPS_DISTANCE; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        GpsConfig cfg = ctx.config().gps();
        if (cfg == null) return EvaluationOutcome.pending(null, null);

        double goalKm = (cfg.goalKm() != null) ? cfg.goalKm().doubleValue() : 0;
        int accuracyMax = (cfg.accuracyMaxM() != null) ? cfg.accuracyMaxM() : 50;
        Instant windowClose = TimeWindows.startOfDay(ctx.targetDate().plusDays(1), ctx.zone());

        double accM = priorMeters(ctx.priorEvidence());
        Set<String> seen = priorSessions(ctx.priorEvidence());

        if (ctx.signals() != null) {
            for (SyncSignal s : ctx.signals()) {
                if (!SignalType.RUNNING_SESSION.name().equals(s.type()) || s.points() == null) continue;
                String key = (s.sessionStart() != null) ? s.sessionStart() : String.valueOf(s.points().hashCode());
                if (seen.contains(key)) continue;           // 멱등: 처리한 세션 스킵
                seen.add(key);
                accM += sessionDistance(s.points(), accuracyMax);
            }
        }

        double km = accM / 1000.0;
        Map<String, Object> ev = new HashMap<>();
        ev.put("distanceKm", round2(km));
        ev.put("goalKm", goalKm);
        ev.put("distanceMeters", round2(accM));
        ev.put("sessions", new ArrayList<>(seen));

        return (km >= goalKm && goalKm > 0)
                ? EvaluationOutcome.success(ev, windowClose)
                : EvaluationOutcome.pending(ev, windowClose);
    }

    /** 한 세션 points 정제 후 haversine 누적(m). 정확도 나쁜 점·비현실 점프 제외. */
    private double sessionDistance(List<GeoPoint> points, int accuracyMax) {
        List<GeoPoint> clean = new ArrayList<>();
        for (GeoPoint p : points) {
            if (p.accuracy() != null && p.accuracy() > accuracyMax) continue;   // 정확도 컷
            clean.add(p);
        }
        clean.sort(Comparator.comparing(p -> nz(TimeWindows.parseInstant(p.at()))));
        double sum = 0;
        for (int i = 1; i < clean.size(); i++) {
            double d = Haversine.meters(clean.get(i - 1).lat(), clean.get(i - 1).lng(),
                    clean.get(i).lat(), clean.get(i).lng());
            if (d > 300) continue;     // 연속 점프(>300m/샘플) 제외 — 측위 튐
            sum += d;
        }
        return sum;
    }

    private double priorMeters(Map<String, Object> prior) {
        Object v = (prior != null) ? prior.get("distanceMeters") : null;
        return (v instanceof Number n) ? n.doubleValue() : 0;
    }
    @SuppressWarnings("unchecked")
    private Set<String> priorSessions(Map<String, Object> prior) {
        Set<String> set = new HashSet<>();
        if (prior != null && prior.get("sessions") instanceof List<?> l)
            for (Object o : l) set.add(String.valueOf(o));
        return set;
    }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private Instant nz(Instant i) { return (i != null) ? i : Instant.EPOCH; }
}
