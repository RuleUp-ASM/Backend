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
 *  - 방문(도달형): 지오펜스 체류 ≥ dwellMinutes. ENTER/EXIT 페어 누적 ≥ 목표.
 *    DWELL 은 「그 시각에 아직 안에 있었다」는 근거로만 쓴다 — 열린 ENTER 를 그 시각까지 체류로 센다.
 *    DWELL 하나로 즉시 성공시키던 때는 ENTER 몇 초 뒤에 온 DWELL 로 0~13분 체류가 30·60분 목표를
 *    통과했다(prod 10/22~10/28). 앱이 sync 마다 지오펜스를 다시 등록하고 INITIAL_TRIGGER_DWELL 이라,
 *    이미 안에 있다고 판단되면 loiteringDelay 를 기다리지 않고 DWELL 이 바로 온다.
 *    fallback LOCATION: 멤버 앵커(OR) 반경 내 측위 시간폭 근사(§7.2, 부록 A).
 *  - 장소 피하기(AVOID)는 2026-10-15 유형째 제거했다 — 방문형만 남는다.
 *
 *  앵커는 PER_MEMBER(challenge_members.anchors). 트랜지션은 geofenceId=challengeMemberId라
 *  좌표 없이도 처리되고, 좌표는 LOCATION fallback 반경 판정에서만 쓴다(멤버 앵커 OR, 없으면 config 레거시 단일앵커).
 */
@Component
public class GpsPresenceEvaluator implements MethodEvaluator {

    /**
     * LOCATION fallback 연속 체류 판정: 반경 내 연속 두 포인트 간 이 값 이하 간격이면 체류로 이어붙인다(초).
     *
     * <p>600초였다. 앱은 sync 한 번(약 30분)에 BALANCED 측위를 한 번 찍으므로 포인트 간격이 늘 600초를
     * 넘어 체류가 거의 쌓이지 않았다(QA SIG-24). 1차로 서버가 앱 수집 주기를 받아 주기로 했다(2026-09-19) —
     * 30분 주기에 WorkManager 지연을 얹어 45분까지 잇는다. 최종 기준은 오탐률·배터리 데이터를 보고
     * Android 와 다시 정한다.
     */
    static final long LOCATION_CONTINUITY_GAP_SECONDS = 45 * 60;
    /** evidence 에 남길 처리 트랜지션 키 상한(설명용, 방어적 캡). */
    private static final int SEEN_TRANSITIONS_CAP = 500;

    @Override
    public VerificationMethod method() { return VerificationMethod.GPS_PRESENCE; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        GpsConfig cfg = ctx.config().gps();
        if (cfg == null) return EvaluationOutcome.pending(null, null);

        Instant windowClose = TimeWindows.startOfDay(ctx.targetDate().plusDays(1), ctx.zone());
        List<GeofenceTransition> trans = collectTransitions(ctx);
        trans.sort(Comparator.comparing(t -> nz(safe(t.at()))));

        // ===== 도달형: dwell 누적 =====
        int goalMin = (cfg.dwellMinutes() != null) ? cfg.dwellMinutes() : 0;
        long dwellSec = 0;
        Instant openEnter = null;
        Instant stillInsideAt = null;   // 열린 ENTER 이후 마지막 DWELL — 그때까지는 안에 있었다
        String source = "TRANSITION";

        // ② 같은 전환이 두 행으로 남아 있어도(geofenceId|transition|at) 한 번만 센다.
        //    그날 원본을 전량 재평가하므로 이월이 아니라 이 평가 안에서의 중복 제거다.
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        for (GeofenceTransition t : trans) {
            Instant at = safe(t.at());
            if (at == null) continue;
            if (!seen.add(transitionKey(t))) continue;                 // 이미 처리한 트랜지션 → skip(멱등)
            switch (t.transition()) {
                case "DWELL" -> { if (openEnter != null) stillInsideAt = at; }   // ENTER 없는 DWELL 은 근거가 아니다
                case "ENTER" -> { if (openEnter == null) openEnter = at; }
                case "EXIT" -> {
                    if (openEnter != null) { dwellSec += Math.max(at.getEpochSecond() - openEnter.getEpochSecond(), 0); openEnter = null; }
                    stillInsideAt = null;
                }
                default -> { }
            }
        }
        // 아직 나가지 않았으면 마지막 DWELL 시각까지만 센다 — 「지금」까지 세면 EXIT 를 놓친 날이 통째로 체류가 된다.
        if (openEnter != null && stillInsideAt != null) {
            dwellSec += Math.max(stillInsideAt.getEpochSecond() - openEnter.getEpochSecond(), 0);
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
        boolean success = dwellMin >= goalMin;

        Map<String, Object> ev = new HashMap<>();
        ev.put("dwellMinutes", dwellMin);
        // 목표를 함께 남긴다 — 실패 설명이 「체류 42분 / 목표 60분」으로 나가려면 판정 당시
        // 기준값이 evidence 에 있어야 한다(공통 5-8). 기준은 나중에 조정될 수 있다.
        ev.put("goalMinutes", goalMin);
        ev.put("insideGeofence", inside);
        ev.put("source", source);
        ev.put("dwellSeconds", dwellSec);
        if (openEnter != null) ev.put("enterAt", openEnter.toString());
        if (openEnter != null && stillInsideAt != null) ev.put("stillInsideAt", stillInsideAt.toString());
        if (lastInside != null) ev.put("lastInsideAt", lastInside.toString());
        if (!seen.isEmpty()) ev.put("seenTransitions", capSeen(seen));
        putHygiene(ev, ctx, cfg);

        return success
                ? EvaluationOutcome.success(ev, windowClose)
                : EvaluationOutcome.pending(ev, windowClose);
    }

    /**
     * 이 멤버(=memberId)의 지오펜스 전환만 수집한다.
     * geofenceId=challengeMemberId 계약(§6.2)상 sync에 여러 챌린지 전환이 섞여 오므로, 여기서 memberId로
     * 필터하지 않으면 다른 챌린지 지오펜스의 ENTER/DWELL이 이 챌린지를 인증시킨다(교차 인증 버그).
     * memberId가 없으면(레거시/단일 멤버 컨텍스트) 필터하지 않는다.
     */
    private List<GeofenceTransition> collectTransitions(DayContext ctx) {
        List<GeofenceTransition> out = new ArrayList<>();
        List<SyncSignal> signals = ctx.signals();
        String memberId = ctx.memberId();
        if (signals == null) return out;
        for (SyncSignal s : signals) {
            if (!("GEOFENCE".equals(s.type()) || "GEOFENCE_TRANSITION".equals(s.type())) || s.transitions() == null) {
                continue;
            }
            for (GeofenceTransition t : s.transitions()) {
                if (untrustedLocation(t.isMock())) continue;   // 조작된 위치·출처 불명은 판정 근거가 아니다(§9.1)
                if (memberId == null || t.belongsTo(memberId, ctx.userId(), ctx.challengeId())) out.add(t);
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
     *  - 사이에 <b>반경 밖</b> 포인트가 끼면 연속이 끊긴다. 간격 허용치가 짧을 때는 드러나지 않았지만,
     *    45분으로 넓히면 "안→밖→안" 이 통째로 체류로 잡힌다. 저정확도·조작 포인트는 안인지 밖인지
     *    모르는 값이라 연속을 끊지도 잇지도 않는다.
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

        boolean leftSince = false;   // 마지막 반경 내 포인트 이후 반경 밖 포인트가 있었는가
        for (GeoPoint p : pts) {
            Instant at = safe(p.at());
            if (lastInside != null && !at.isAfter(lastInside)) continue;   // 멱등 워터마크(이미 반영된 시각)
            if (!usableForDwell(p, cfg)) continue;                         // 조작·저정확도는 판정 근거가 아니다
            if (!insideAny(p, use)) { leftSince = true; continue; }        // 반경 밖 → 체류 아님, 연속도 끊긴다
            if (lastInside != null && !leftSince) {
                long delta = at.getEpochSecond() - lastInside.getEpochSecond();
                if (delta > 0 && delta <= LOCATION_CONTINUITY_GAP_SECONDS) { dwellSec += delta; added += delta; }
            }
            lastInside = at;
            leftSince = false;
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
