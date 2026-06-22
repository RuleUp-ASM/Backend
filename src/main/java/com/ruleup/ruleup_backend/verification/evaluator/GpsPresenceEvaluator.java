package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.GpsConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.signal.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * GPS PRESENCE 평가기 (§2.6) — 도달형. 지오펜스 체류 ≥ dwellMinutes.
 *  - 주신호 GEOFENCE: DWELL 트랜지션 = OS가 loiteringDelay(=dwellMinutes) 체류 확정 → 즉시 SUCCESS.
 *    ENTER→EXIT 구간은 누적해 dwell 합산(evidence 상태 유지, 증분).
 *  - fallback LOCATION: 반경 내 측위 포인트의 시간 폭으로 체류 근사.
 */
@Component
public class GpsPresenceEvaluator implements MethodEvaluator {

    @Override
    public VerificationMethod method() { return VerificationMethod.GPS_PRESENCE; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        GpsConfig cfg = ctx.config().gps();
        if (cfg == null) return EvaluationOutcome.pending(null, null);

        int goalMin = (cfg.dwellMinutes() != null) ? cfg.dwellMinutes() : 0;
        Instant windowClose = TimeWindows.startOfDay(ctx.targetDate().plusDays(1), ctx.zone());

        long dwellSec = priorSeconds(ctx.priorEvidence());
        Instant openEnter = priorEnter(ctx.priorEvidence());
        boolean dwellConfirmed = false;
        String source = "TRANSITION";

        // GEOFENCE 트랜지션 처리
        List<GeofenceTransition> trans = collectTransitions(ctx.signals());
        trans.sort(Comparator.comparing(t -> safe(t.at())));
        for (GeofenceTransition t : trans) {
            Instant at = safe(t.at());
            if (at == null) continue;
            switch (t.transition()) {
                case "DWELL" -> dwellConfirmed = true;                 // OS 체류 확정
                case "ENTER" -> { if (openEnter == null) openEnter = at; }
                case "EXIT" -> {
                    if (openEnter != null) { dwellSec += Math.max(at.getEpochSecond() - openEnter.getEpochSecond(), 0); openEnter = null; }
                }
                default -> { }
            }
        }

        // fallback: 트랜지션 전무 + LOCATION 포인트가 반경 내면 그 시간 폭으로 근사
        if (trans.isEmpty() && cfg.lat() != null && cfg.lng() != null) {
            long approx = locationDwellSeconds(ctx.signals(), cfg);
            if (approx > 0) { dwellSec += approx; source = "POINTS"; }
        }

        long dwellMin = dwellSec / 60;
        boolean inside = openEnter != null;
        boolean success = dwellConfirmed || dwellMin >= goalMin;

        Map<String, Object> ev = new HashMap<>();
        ev.put("dwellMinutes", dwellMin);
        ev.put("insideGeofence", inside);
        ev.put("source", source);
        ev.put("dwellSeconds", dwellSec);
        if (openEnter != null) ev.put("enterAt", openEnter.toString());

        return success
                ? EvaluationOutcome.success(ev, windowClose)
                : EvaluationOutcome.pending(ev, windowClose);
    }

    private List<GeofenceTransition> collectTransitions(List<SyncSignal> signals) {
        List<GeofenceTransition> out = new ArrayList<>();
        if (signals == null) return out;
        for (SyncSignal s : signals) {
            if (SignalType.GEOFENCE.name().equals(s.type()) && s.transitions() != null) out.addAll(s.transitions());
        }
        return out;
    }

    /** 반경 내 LOCATION 포인트의 [첫,마지막] 시간 폭(초). 거친 근사. */
    private long locationDwellSeconds(List<SyncSignal> signals, GpsConfig cfg) {
        if (signals == null) return 0;
        Instant first = null, last = null;
        int radius = (cfg.radiusM() != null) ? cfg.radiusM() : 100;
        for (SyncSignal s : signals) {
            if (!SignalType.LOCATION.name().equals(s.type()) || s.points() == null) continue;
            for (GeoPoint p : s.points()) {
                if (Haversine.meters(cfg.lat(), cfg.lng(), p.lat(), p.lng()) > radius) continue;
                Instant at = safe(p.at());
                if (at == null) continue;
                if (first == null || at.isBefore(first)) first = at;
                if (last == null || at.isAfter(last)) last = at;
            }
        }
        return (first != null && last != null) ? Math.max(last.getEpochSecond() - first.getEpochSecond(), 0) : 0;
    }

    private long priorSeconds(Map<String, Object> prior) {
        Object v = (prior != null) ? prior.get("dwellSeconds") : null;
        return (v instanceof Number n) ? n.longValue() : 0;
    }
    private Instant priorEnter(Map<String, Object> prior) {
        Object v = (prior != null) ? prior.get("enterAt") : null;
        return (v != null) ? safe(v.toString()) : null;
    }
    private Instant safe(String iso) { return TimeWindows.parseInstant(iso); }
}
