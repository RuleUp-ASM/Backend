package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.common.verification.GeoAnchor;
import com.ruleup.ruleup_backend.verification.domain.GpsConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.signal.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * GPS PRESENCE 평가기(테크스펙 v2 §5, §7.2).
 *  - VISIT(도달형): 지오펜스 체류 ≥ dwellMinutes. DWELL 트랜지션(OS가 loiteringDelay 체류 확정)→즉시 SUCCESS.
 *    DWELL 없으면 ENTER/EXIT 페어 누적 ≥ 목표. fallback LOCATION: 멤버 앵커(OR) 반경 내 측위 시간폭 근사(§7.2, 부록 A).
 *  - AVOID(제약형, v2): 창/하루 내 ENTER가 하나라도 있으면 즉시 FAILED(ENTERED_AVOID_ZONE). 없으면 마감 배치가 SUCCESS.
 *
 *  앵커는 PER_MEMBER(challenge_members.anchors). 트랜지션은 geofenceId=challengeMemberId라
 *  좌표 없이도 처리되고, 좌표는 LOCATION fallback 반경 판정에서만 쓴다(멤버 앵커 OR, 없으면 config 레거시 단일앵커).
 */
@Component
public class GpsPresenceEvaluator implements MethodEvaluator {

    @Override
    public VerificationMethod method() { return VerificationMethod.GPS_PRESENCE; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        GpsConfig cfg = ctx.config().gps();
        if (cfg == null) return EvaluationOutcome.pending(null, null);

        Instant windowClose = TimeWindows.startOfDay(ctx.targetDate().plusDays(1), ctx.zone());
        List<GeofenceTransition> trans = collectTransitions(ctx.signals());
        trans.sort(Comparator.comparing(t -> nz(safe(t.at()))));

        // ===== AVOID(제약형): 창 내 ENTER 1건이라도 → 즉시 FAILED =====
        if (cfg.isAvoid()) {
            boolean entered = trans.stream().anyMatch(t -> "ENTER".equals(t.transition()) || "DWELL".equals(t.transition()));
            Map<String, Object> ev = new HashMap<>();
            ev.put("avoid", true);
            ev.put("entered", entered);
            return entered
                    ? EvaluationOutcome.failed("ENTERED_AVOID_ZONE", ev, windowClose)
                    : EvaluationOutcome.pending(ev, windowClose);   // 무위반은 마감 배치가 SUCCESS 확정
        }

        // ===== VISIT(도달형): dwell 누적 =====
        int goalMin = (cfg.dwellMinutes() != null) ? cfg.dwellMinutes() : 0;
        long dwellSec = priorSeconds(ctx.priorEvidence());
        Instant openEnter = priorEnter(ctx.priorEvidence());
        boolean dwellConfirmed = false;
        String source = "TRANSITION";

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

        // fallback: 트랜지션 전무 → 멤버 앵커(OR) 반경 내 LOCATION 포인트 시간폭으로 근사
        if (trans.isEmpty()) {
            long approx = locationDwellSeconds(ctx.signals(), ctx.memberAnchors(), cfg);
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

    /**
     * 멤버 앵커(OR) 중 어느 하나의 반경 내에 있는 LOCATION 포인트들의 [첫,마지막] 시간폭(초). 거친 근사.
     * 앵커가 없으면 config 레거시 단일앵커(lat/lng/radiusM)로 폴백.
     */
    private long locationDwellSeconds(List<SyncSignal> signals, List<GeoAnchor> anchors, GpsConfig cfg) {
        if (signals == null) return 0;
        List<GeoAnchor> use = effectiveAnchors(anchors, cfg);
        if (use.isEmpty()) return 0;

        Instant first = null, last = null;
        for (SyncSignal s : signals) {
            if (!SignalType.LOCATION.name().equals(s.type()) || s.points() == null) continue;
            for (GeoPoint p : s.points()) {
                if (!insideAny(p, use)) continue;
                Instant at = safe(p.at());
                if (at == null) continue;
                if (first == null || at.isBefore(first)) first = at;
                if (last == null || at.isAfter(last)) last = at;
            }
        }
        return (first != null && last != null) ? Math.max(last.getEpochSecond() - first.getEpochSecond(), 0) : 0;
    }

    private List<GeoAnchor> effectiveAnchors(List<GeoAnchor> anchors, GpsConfig cfg) {
        if (anchors != null && !anchors.isEmpty()) return anchors;
        if (cfg.lat() != null && cfg.lng() != null) {
            int r = (cfg.radiusM() != null) ? cfg.radiusM() : 100;
            return List.of(new GeoAnchor(cfg.lat(), cfg.lng(), r, "legacy"));
        }
        return List.of();
    }

    private boolean insideAny(GeoPoint p, List<GeoAnchor> anchors) {
        for (GeoAnchor a : anchors) {
            if (Haversine.meters(a.lat(), a.lng(), p.lat(), p.lng()) <= a.radiusM()) return true;
        }
        return false;
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
    private Instant nz(Instant i) { return (i != null) ? i : Instant.EPOCH; }
}
