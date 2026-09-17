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
    /** evidence 에 남길 처리 트랜지션 키 상한(설명용, 방어적 캡). */
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
        long dwellSec = 0;
        Instant openEnter = null;
        boolean dwellConfirmed = false;
        String source = "TRANSITION";

        // ② 같은 전환이 두 행으로 남아 있어도(geofenceId|transition|at) 한 번만 센다.
        //    그날 원본을 전량 재평가하므로 이월이 아니라 이 평가 안에서의 중복 제거다.
        LinkedHashSet<String> seen = new LinkedHashSet<>();

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

        // ① fallback: 트랜지션 전무 → 멤버 앵커(OR) 반경 내 LOCATION 포인트로 연속 체류를 누적.
        //    그날 포인트를 전부 들고 있으므로 이월 워터마크 없이 한 번에 이어붙인다.
        Instant lastInside = null;
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
        // 목표를 함께 남긴다 — 실패 설명이 「체류 42분 / 목표 60분」으로 나가려면 판정 당시
        // 기준값이 evidence 에 있어야 한다(공통 5-8). 기준은 나중에 조정될 수 있다.
        ev.put("goalMinutes", goalMin);
        ev.put("insideGeofence", inside);
        ev.put("source", source);
        ev.put("dwellSeconds", dwellSec);
        if (openEnter != null) ev.put("enterAt", openEnter.toString());
        if (lastInside != null) ev.put("lastInsideAt", lastInside.toString());
        if (!seen.isEmpty()) ev.put("seenTransitions", capSeen(seen));
        putHygiene(ev, ctx, cfg);

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
        Instant openEnter = null;
        long longestStaySec = 0;

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
        ev.put("dwellSeconds", longestStaySec);          // 가장 오래 머문 시간
        if (openEnter != null) ev.put("enterAt", openEnter.toString());
        putHygiene(ev, ctx, cfg);
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
                if (untrustedLocation(t.isMock())) continue;   // 조작된 위치·출처 불명은 판정 근거가 아니다(§9.1)
                if (memberId == null || memberId.equals(t.geofenceId())) out.add(t);
            }
        }
        return out;
    }

    /**
     * 판정에서 <b>뺀</b> 신호 수를 evidence 에 누적한다 — 신호 위생 층의 기록이다(공통 5-3).
     *
     * <p>판정 로직은 건드리지 않는다. 조작된 위치와 저정확도 측위는 이미 근거에서 빠져 있고,
     * 여기서는 <b>몇 개가 빠졌는지만</b> 센다. 그 수가 여러 판정에 걸쳐 반복될 때 이상패턴
     * 탐지가 부정행위를 확정한다 — 단건으로는 확정하지 않는다.
     *
     * <p>그날 원본을 전량 재평가하므로 매번 처음부터 센다 — 이월이 없어 같은 신호가 두 번
     * 세어지지 않는다. 배제 로그는 <b>확정 시 한 번</b> 이 값을 옮긴다.
     */
    private void putHygiene(Map<String, Object> ev, DayContext ctx, GpsConfig cfg) {
        int mock = 0;
        int lowAccuracy = 0;
        Integer maxAccuracy = cfg.accuracyMaxM();

        if (ctx.signals() != null) {
            for (SyncSignal s : ctx.signals()) {
                if (s.transitions() != null) {
                    for (GeofenceTransition t : s.transitions()) {
                        if (t != null && untrustedLocation(t.isMock())) mock++;
                    }
                }
                if (s.points() == null) continue;
                for (GeoPoint p : s.points()) {
                    if (p == null) continue;
                    if (untrustedLocation(p.isMock())) mock++;
                    else if (maxAccuracy != null && p.accuracy() != null
                            && p.accuracy() > maxAccuracy) lowAccuracy++;
                }
            }
        }
        if (mock > 0) ev.put("excludedMock", mock);
        if (lowAccuracy > 0) ev.put("excludedAccuracy", lowAccuracy);
    }

    /** LOCATION fallback 누적 결과: 갱신된 총 체류초·이번 배치가 더한 초·이월할 lastInsideAt. */
    private record LocationDwell(long dwellSec, long added, Instant lastInside) {}

    /**
     * 멤버 앵커(OR) 반경 내 LOCATION 포인트로 체류시간을 sync 간 연속 누적한다(테크스펙 v2 §7.2 fallback).
     *  - 반경 내 연속 포인트 간 간격이 LOCATION_CONTINUITY_GAP_SECONDS 이하일 때만 그 간격을
     *    체류로 이어붙인다(공백·이탈은 미가산).
     *  - 직전 반영 시각 이하의 포인트는 무시 → 같은 좌표가 두 행으로 남아 있어도 이중 누적하지 않는다.
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
        if (untrustedLocation(p.isMock())) return false;
        Integer maxAccuracy = cfg.accuracyMaxM();
        return maxAccuracy == null || p.accuracy() == null || p.accuracy() <= maxAccuracy;
    }

    /**
     * 이 좌표를 판정 근거로 쓸 수 없는가 — <b>모름({@code null})도 쓸 수 없다</b>.
     *
     * <p>{@code isMock} 은 위치 신호의 필수 필드다(테크스펙 v2 §6.3). 예전에는 {@code TRUE} 만
     * 걸러서 <b>필드를 아예 빼고 보내면 게이트가 그대로 열렸다</b> — 모의 위치를 막는 장치를
     * 필드 하나 생략으로 우회할 수 있었다(QA SIG-02). 판정에서 빼는 것은 부정행위 확정과
     * 분리된 층이라(§9.1), 모르는 출처는 조용히 근거에서 뺀다.
     */
    private static boolean untrustedLocation(Boolean isMock) {
        return !Boolean.FALSE.equals(isMock);
    }

    private boolean insideAny(GeoPoint p, List<GeoAnchor> anchors) {
        for (GeoAnchor a : anchors) {
            if (Haversine.meters(a.lat(), a.lng(), p.lat(), p.lng()) <= a.radiusM()) return true;
        }
        return false;
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
