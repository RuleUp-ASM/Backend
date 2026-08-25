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

    /** LOCATION fallback 연속 체류 판정: 반경 내 연속 두 포인트 간 이 값 이하 간격이면 체류로 이어붙인다(초). */
    private static final long LOCATION_CONTINUITY_GAP_SECONDS = 600;
    /** 신호 단위 멱등: evidence에 이월하는 처리 완료 트랜지션 키 상한(방어적 캡). */
    private static final int SEEN_TRANSITIONS_CAP = 500;

    @Override
    public VerificationMethod method() { return VerificationMethod.GPS_PRESENCE; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        GpsConfig cfg = ctx.config().gps();
        if (cfg == null) return EvaluationOutcome.pending(null, null);

        Instant windowClose = TimeWindows.startOfDay(ctx.targetDate().plusDays(1), ctx.zone());
        List<GeofenceTransition> trans = collectTransitions(ctx.signals(), ctx.memberId());
        trans.sort(Comparator.comparing(t -> nz(safe(t.at()))));

        // ===== AVOID(제약형): 유효한 진입만 위반. 허용 시간 안에 나왔으면 "스침"이다 =====
        if (cfg.isAvoid()) return evaluateAvoid(ctx, cfg, trans, windowClose);

        // ===== VISIT(도달형): dwell 누적 =====
        int goalMin = (cfg.dwellMinutes() != null) ? cfg.dwellMinutes() : 0;
        long dwellSec = priorSeconds(ctx.priorEvidence());
        Instant openEnter = priorEnter(ctx.priorEvidence());
        boolean dwellConfirmed = false;
        String source = "TRANSITION";

        // ② 신호 단위 멱등: (geofenceId|transition|at) 키로 이미 처리한 트랜지션은 재누적하지 않는다.
        //    prior에 이월된 키 + 이번 배치 키를 합쳐 재전송/중복 배치의 ENTER·EXIT 이중 계산을 차단(§0.1 재전송 안전).
        LinkedHashSet<String> seen = new LinkedHashSet<>(priorSeen(ctx.priorEvidence()));

        for (GeofenceTransition t : trans) {
            Instant at = safe(t.at());
            if (at == null) continue;
            if (!seen.add(transitionKey(t))) continue;                 // 이미 처리한 트랜지션 → skip(멱등)
            switch (t.transition()) {
                case "DWELL" -> dwellConfirmed = true;                 // OS 체류 확정
                case "ENTER" -> { if (openEnter == null) openEnter = at; }
                case "EXIT" -> {
                    if (openEnter != null) { dwellSec += Math.max(at.getEpochSecond() - openEnter.getEpochSecond(), 0); openEnter = null; }
                }
                default -> { }
            }
        }

        // ① fallback: 트랜지션 전무 → 멤버 앵커(OR) 반경 내 LOCATION 포인트를 sync 간 연속으로 누적.
        //    배치당 1포인트여도 lastInsideAt(직전 반경 내 관측 시각)을 evidence로 이월해 연속 체류를 이어붙인다.
        Instant lastInside = priorLastInside(ctx.priorEvidence());
        if (trans.isEmpty()) {
            LocationDwell ld = locationDwell(ctx.signals(), ctx.memberAnchors(), cfg, dwellSec, lastInside);
            if (ld.added() > 0) source = "POINTS";
            dwellSec = ld.dwellSec();
            lastInside = ld.lastInside();
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
        if (lastInside != null) ev.put("lastInsideAt", lastInside.toString());   // ① 연속성 이월
        if (!seen.isEmpty()) ev.put("seenTransitions", capSeen(seen));            // ② 멱등 키 이월

        return success
                ? EvaluationOutcome.success(ev, windowClose)
                : EvaluationOutcome.pending(ev, windowClose);
    }

    /**
     * AVOID(장소 피하기) 판정 — 진입했다고 바로 위반이 아니다.
     *
     * <p>정책은 "허용 시간 안에 이탈한 것이 확인되면 스침으로 처리"다. 금지 장소 앞을 지나가기만 해도
     * 지오펜스는 ENTER 를 쏘기 때문에, 그대로 위반 처리하면 편의점 앞을 지난 사람이 실패한다.
     *
     * <ul>
     *   <li>ENTER→EXIT 쌍의 체류가 허용 시간 이하 → 스침(위반 아님)</li>
     *   <li>체류가 허용 시간 초과 → 위반</li>
     *   <li>아직 EXIT 이 안 왔고 허용 시간도 안 지났으면 판단 보류 — 이탈 신호가 늦게 올 수 있다</li>
     *   <li>OS 가 체류를 확정한 DWELL 은 유예 없이 위반 — 이미 "머물렀다"는 판정이다</li>
     * </ul>
     * 허용 시간은 서버 설정({@code loiteringDelayMin})이라 실기기 테스트로 조절할 수 있다.
     */
    private EvaluationOutcome evaluateAvoid(DayContext ctx, GpsConfig cfg,
                                            List<GeofenceTransition> trans, Instant windowClose) {
        long graceSec = 60L * ((cfg.loiteringDelayMin() != null) ? cfg.loiteringDelayMin() : 0);
        boolean violated = false;
        Instant openEnter = priorEnter(ctx.priorEvidence());
        long longestStaySec = priorSeconds(ctx.priorEvidence());

        for (GeofenceTransition t : trans) {
            Instant at = safe(t.at());
            if (at == null) continue;
            switch (nzStr(t.transition())) {
                case "DWELL" -> violated = true;
                case "ENTER" -> { if (openEnter == null) openEnter = at; }
                case "EXIT" -> {
                    if (openEnter != null) {
                        long staySec = Math.max(at.getEpochSecond() - openEnter.getEpochSecond(), 0);
                        longestStaySec = Math.max(longestStaySec, staySec);
                        if (staySec > graceSec) violated = true;
                        openEnter = null;
                    }
                }
                default -> { }
            }
        }
        // 아직 안 나온 진입: 허용 시간을 이미 넘겼으면 이탈 신호를 기다릴 것 없이 위반이다.
        if (!violated && openEnter != null) {
            long stayedSec = Math.max(ctx.now().getEpochSecond() - openEnter.getEpochSecond(), 0);
            longestStaySec = Math.max(longestStaySec, stayedSec);
            if (stayedSec > graceSec) violated = true;
        }

        Map<String, Object> ev = new HashMap<>();
        ev.put("avoid", true);
        ev.put("entered", violated);
        ev.put("graceMinutes", graceSec / 60);
        ev.put("dwellSeconds", longestStaySec);          // 가장 오래 머문 시간(이월)
        if (openEnter != null) ev.put("enterAt", openEnter.toString());
        return violated
                ? EvaluationOutcome.violated("ENTERED_AVOID_ZONE", ev, windowClose)
                : EvaluationOutcome.pending(ev, windowClose);   // 무위반은 확정 배치가 SUCCESS 로 잠근다
    }

    /**
     * 이 멤버(=memberId)의 지오펜스 전환만 수집한다.
     * geofenceId=challengeMemberId 계약(§6.2)상 sync에 여러 챌린지 전환이 섞여 오므로, 여기서 memberId로
     * 필터하지 않으면 다른 챌린지 지오펜스의 ENTER/DWELL이 이 챌린지를 인증(또는 AVOID 위반)시킨다(교차 인증 버그).
     * memberId가 없으면(레거시/단일 멤버 컨텍스트) 필터하지 않는다.
     */
    private List<GeofenceTransition> collectTransitions(List<SyncSignal> signals, String memberId) {
        List<GeofenceTransition> out = new ArrayList<>();
        if (signals == null) return out;
        for (SyncSignal s : signals) {
            if (!("GEOFENCE".equals(s.type()) || "GEOFENCE_TRANSITION".equals(s.type())) || s.transitions() == null) {
                continue;
            }
            for (GeofenceTransition t : s.transitions()) {
                if (Boolean.TRUE.equals(t.isMock())) continue;   // 조작된 위치는 판정 근거가 아니다(§9.1)
                if (memberId == null || memberId.equals(t.geofenceId())) out.add(t);
            }
        }
        return out;
    }

    /** LOCATION fallback 누적 결과: 갱신된 총 체류초·이번 배치가 더한 초·이월할 lastInsideAt. */
    private record LocationDwell(long dwellSec, long added, Instant lastInside) {}

    /**
     * 멤버 앵커(OR) 반경 내 LOCATION 포인트로 체류시간을 sync 간 연속 누적한다(테크스펙 v2 §7.2 fallback).
     *  - lastInsideAt(직전 반경 내 관측 시각)을 prior로 받아, 반경 내 연속 포인트 간 간격이
     *    LOCATION_CONTINUITY_GAP_SECONDS 이하일 때만 그 간격을 체류로 이어붙인다(공백·이탈은 미가산).
     *  - lastInsideAt 이하 시각의 포인트는 무시 → 재전송·중복 포인트 이중 누적 방지(멱등).
     * 앵커가 없으면 config 레거시 단일앵커(lat/lng/radiusM)로 폴백.
     */
    private LocationDwell locationDwell(List<SyncSignal> signals, List<GeoAnchor> anchors, GpsConfig cfg,
                                        long dwellSecIn, Instant priorLastInside) {
        long dwellSec = dwellSecIn;
        long added = 0;
        Instant lastInside = priorLastInside;
        List<GeoAnchor> use = effectiveAnchors(anchors, cfg);
        if (signals == null || use.isEmpty()) return new LocationDwell(dwellSec, 0, lastInside);

        List<GeoPoint> pts = new ArrayList<>();
        for (SyncSignal s : signals) {
            if (!SignalType.LOCATION.name().equals(s.type()) || s.points() == null) continue;
            for (GeoPoint p : s.points()) if (safe(p.at()) != null) pts.add(p);
        }
        pts.sort(Comparator.comparing(p -> nz(safe(p.at()))));

        for (GeoPoint p : pts) {
            Instant at = safe(p.at());
            if (lastInside != null && !at.isAfter(lastInside)) continue;   // 멱등 워터마크(이미 반영된 시각)
            if (!usableForDwell(p, cfg)) continue;                         // 조작·저정확도는 판정 근거가 아니다
            if (!insideAny(p, use)) continue;                              // 반경 밖은 체류 아님
            if (lastInside != null) {
                long delta = at.getEpochSecond() - lastInside.getEpochSecond();
                if (delta > 0 && delta <= LOCATION_CONTINUITY_GAP_SECONDS) { dwellSec += delta; added += delta; }
            }
            lastInside = at;
        }
        return new LocationDwell(dwellSec, added, lastInside);
    }

    private List<GeoAnchor> effectiveAnchors(List<GeoAnchor> anchors, GpsConfig cfg) {
        if (anchors != null && !anchors.isEmpty()) return anchors;
        if (cfg.lat() != null && cfg.lng() != null) {
            int r = (cfg.radiusM() != null) ? cfg.radiusM() : 100;
            return List.of(new GeoAnchor(cfg.lat(), cfg.lng(), r, "legacy"));
        }
        return List.of();
    }

    /**
     * 체류 근거로 쓸 수 있는 측위인지 (테크 스펙 §5-1 "GPS 정확도가 허용 기준보다 나쁜 정보는 판정 근거에서 제외").
     *
     * <p>정확도가 반경만큼 나쁘면 그 좌표가 정말 안에 있었는지 알 수 없다 — 반경 판정이 동전 던지기가 된다.
     * 조작된 위치(mock)는 애초에 근거가 아니다. 둘 다 "제외"일 뿐 부정행위 확정과는 분리한다(§9.1).
     */
    private boolean usableForDwell(GeoPoint p, GpsConfig cfg) {
        if (Boolean.TRUE.equals(p.isMock())) return false;
        Integer maxAccuracy = cfg.accuracyMaxM();
        return maxAccuracy == null || p.accuracy() == null || p.accuracy() <= maxAccuracy;
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
    @SuppressWarnings("unchecked")
    private List<String> priorSeen(Map<String, Object> prior) {
        Object v = (prior != null) ? prior.get("seenTransitions") : null;
        return (v instanceof List<?> l) ? (List<String>) (List<?>) l : List.of();
    }
    private Instant priorLastInside(Map<String, Object> prior) {
        Object v = (prior != null) ? prior.get("lastInsideAt") : null;
        return (v != null) ? safe(v.toString()) : null;
    }
    private String transitionKey(GeofenceTransition t) {
        return nzStr(t.geofenceId()) + "|" + nzStr(t.transition()) + "|" + nzStr(t.at());
    }
    private List<String> capSeen(java.util.Collection<String> keys) {
        List<String> all = new ArrayList<>(keys);
        return (all.size() <= SEEN_TRANSITIONS_CAP) ? all
                : new ArrayList<>(all.subList(all.size() - SEEN_TRANSITIONS_CAP, all.size()));
    }
    private String nzStr(String s) { return (s != null) ? s : ""; }
    private Instant safe(String iso) { return TimeWindows.parseInstant(iso); }
    private Instant nz(Instant i) { return (i != null) ? i : Instant.EPOCH; }
}
