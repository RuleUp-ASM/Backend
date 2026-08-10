package com.ruleup.ruleup_backend.verification.service;
import com.ruleup.ruleup_backend.common.verification.*;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.domain.SignalSource;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import com.ruleup.ruleup_backend.verification.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 챌린지 저장값(스냅샷 + params + templateId)으로 테크스펙 v2 §12.3 rich VerificationConfig를 조립.
 *  - method 라우팅: RoutineTemplate.verificationMethod(명시 컬럼) 우선 — 캐시(RoutineCatalog)로 조회.
 *  - 명시 컬럼이 없으면 휴리스틱 fallback.
 *  v2 변경:
 *   - 움직임 태그(HEALTH / 레거시 GPS_DISTANCE·ACTIVITY) → VerificationMethod.HEALTH 로 라우팅 + HealthConfig 조립.
 *     (커스텀 세션 GPS_DISTANCE는 보존하되 라우팅 안 함, §3)
 *   - GPS_PRESENCE에 presence(VISIT/AVOID)·anchorFillMode·brandKeyword·nearbyRadiusM 반영. 앵커 좌표는 멤버 바인딩.
 */
@Component
@RequiredArgsConstructor
public class VerificationConfigFactory {

    private static final List<String> DEFAULT_TRUSTED_ORIGINS =
            List.of("com.sec.android.app.shealth", "com.google.android.apps.fitness");

    private final RoutineCatalog catalog;

    public VerificationConfig build(Challenge challenge) {
        var snap = challenge.getVerificationConfig();
        Map<String, Object> params = challenge.getParams();
        List<String> perms = (snap != null) ? snap.requiredPermissions() : List.of();

        String tag = resolveTag(challenge, snap, params);
        VerificationMethod method = methodOf(tag);

        // 일정: 현재 Challenge는 repeatDays(고정요일)만 → FIXED_DAYS. (빈도형 입력 연결은 추후)
        ScheduleType scheduleType = ScheduleType.FIXED_DAYS;
        Frequency frequency = null;

        WakeConfig wake = null;
        ScreenTimeConfig screenTime = null;
        GpsConfig gps = null;
        HealthConfig health = null;
        SleepConfig sleep = null;

        switch (tag) {
            case "WAKE" -> wake = new WakeConfig(
                    timeParam(params, "target_time", "07:00"), Polarity.ACHIEVEMENT, 1);
            case "SCREEN_TIME_MIN" -> screenTime = new ScreenTimeConfig(
                    ScreenTimeMode.MIN, Polarity.ACHIEVEMENT,
                    packages(params), intParam(params, "duration_min", 30), timeWindow(params), 1);
            case "SCREEN_TIME_MAX" -> screenTime = new ScreenTimeConfig(
                    ScreenTimeMode.MAX, Polarity.CONSTRAINT,
                    packages(params), intParam(params, "duration_min", 30), timeWindow(params), 1);
            case "GPS_PRESENCE", "GPS_AVOID" -> gps = buildGps(params, "GPS_AVOID".equals(tag));
            // 움직임: 레거시 GPS_DISTANCE/ACTIVITY 태그도 v2에서는 HEALTH로 라우팅.
            case "HEALTH", "GPS_DISTANCE", "ACTIVITY" -> health = buildHealth(params);
            case "SLEEP" -> sleep = new SleepConfig(
                    timeParamOrNull(params, "bedtime_before"),
                    decimalParamOrNull(params, "sleep_hours"),
                    Polarity.ACHIEVEMENT, 12);
            default -> { /* PHOTO / SELF_CHECK: 수동 */ }
        }

        return new VerificationConfig(
                scheduleType, frequency, MethodCombine.AND, List.of(method),
                gps, health, screenTime, wake, sleep, perms);
    }

    // ===== GPS_PRESENCE (VISIT/AVOID) =====
    /**
     * @param avoidByTemplate 루틴 템플릿이 "장소 피하기"({@code GPS_AVOID})로 정의된 경우.
     *                        방문/회피는 방 단위 인증 방식이라 사용자가 고치는 목표값(params)이 아니라
     *                        템플릿 쪽에 둔다. 레거시 {@code gps_presence} 파라미터도 계속 인정한다.
     */
    private GpsConfig buildGps(Map<String, Object> params, boolean avoidByTemplate) {
        boolean avoid = avoidByTemplate || "AVOID".equalsIgnoreCase(strParam(params, "gps_presence"));
        GpsPresence presence = avoid ? GpsPresence.AVOID : GpsPresence.VISIT;
        Polarity polarity = avoid ? Polarity.CONSTRAINT : Polarity.ACHIEVEMENT;
        AnchorFillMode fillMode = "NEARBY_BRAND".equalsIgnoreCase(strParam(params, "anchor_fill_mode"))
                ? AnchorFillMode.NEARBY_BRAND : AnchorFillMode.MANUAL;
        int dwell = intParam(params, "duration_min", 60);
        return new GpsConfig(
                GpsMode.PRESENCE, presence, fillMode,
                strParam(params, "brand_keyword"), intParam(params, "nearby_radius_m", 1500),
                dbl(params, "lat"), dbl(params, "lng"),       // 레거시 단일앵커(보통 null) — 평가는 멤버 anchors[] 우선
                intParam(params, "radius_m", 80), dwell, dwell,
                null, timeWindow(params),
                polarity, 1, 100, 50, List.of());
    }

    // ===== HEALTH (움직임) =====
    private HealthConfig buildHealth(Map<String, Object> params) {
        HealthMetric metric = metricOf(strParam(params, "metric"), params);
        BigDecimal goal;
        String unit;
        switch (metric) {
            case STEPS -> { goal = decimalParam(params, "steps", BigDecimal.valueOf(6000)); unit = "count"; }
            case EXERCISE_DURATION -> { goal = decimalParam(params, "duration_min", BigDecimal.valueOf(30)); unit = "min"; }
            default -> { goal = decimalParam(params, "distance_km", BigDecimal.valueOf(3)); unit = "km"; }
        }
        return new HealthConfig(
                metric, goal, unit,
                strParam(params, "exercise_type"),     // null=무관
                DEFAULT_TRUSTED_ORIGINS,
                true,                                  // recordingMethod==MANUAL 거부
                Polarity.ACHIEVEMENT, 2);
    }

    private HealthMetric metricOf(String raw, Map<String, Object> params) {
        if (raw != null) {
            try { return HealthMetric.valueOf(raw.trim().toUpperCase()); } catch (Exception ignored) { }
        }
        if (params != null) {
            if (params.containsKey("steps")) return HealthMetric.STEPS;
            if (params.containsKey("duration_min") && !params.containsKey("distance_km")) return HealthMetric.EXERCISE_DURATION;
        }
        return HealthMetric.DISTANCE;
    }

    // ===== method 태그 결정 =====
    private String resolveTag(Challenge challenge, Object snapObj, Map<String, Object> params) {
        Long templateId = challenge.getTemplateId();
        if (templateId != null) {
            RoutineTemplate t = catalog.findById(templateId).orElse(null);
            if (t != null && t.getVerificationMethod() != null && !t.getVerificationMethod().isBlank()) {
                return t.getVerificationMethod();   // 명시 컬럼(정답)
            }
        }
        return heuristicTag(snapObj, params);       // fallback
    }

    private VerificationMethod methodOf(String tag) {
        return switch (tag) {
            case "WAKE" -> VerificationMethod.WAKE;
            case "SCREEN_TIME_MIN", "SCREEN_TIME_MAX" -> VerificationMethod.SCREEN_TIME;
            // 방문(VISIT)·회피(AVOID) 는 같은 평가기가 처리한다 — 방향만 GpsConfig 에 실린다.
            case "GPS_PRESENCE", "GPS_AVOID" -> VerificationMethod.GPS_PRESENCE;
            // v2: 움직임은 전부 HEALTH로. 커스텀 세션(GPS_DISTANCE 평가기)은 보존하되 라우팅 제외.
            case "HEALTH", "GPS_DISTANCE", "ACTIVITY" -> VerificationMethod.HEALTH;
            case "SLEEP" -> VerificationMethod.SLEEP;
            case "SELF_CHECK" -> VerificationMethod.SELF_CHECK;
            default -> VerificationMethod.PHOTO;
        };
    }

    /** 명시 컬럼이 없을 때만 쓰는 안전망(템플릿 매칭 실패·직접 입력). */
    private String heuristicTag(Object snapObj, Map<String, Object> params) {
        if (snapObj == null) return "PHOTO";
        var snap = (com.ruleup.ruleup_backend.routine.domain.VerificationConfig) snapObj;
        if (snap.selectedMethod() == SelectedMethod.MANUAL) {
            return snap.signalSource() == SignalSource.PHOTO ? "PHOTO" : "SELF_CHECK";
        }
        SignalSource src = snap.signalSource();
        if (src == null) return "PHOTO";
        return switch (src) {
            case GEOFENCE -> "GPS_PRESENCE";
            case GPS, ACTIVITY, HC_RECORD -> "HEALTH";   // v2: 움직임은 Health Connect
            case SLEEP -> "SLEEP";
            case USAGE -> (params != null && params.containsKey("target_time")) ? "WAKE" : "SCREEN_TIME_MIN";
            default -> "PHOTO";
        };
    }

    // ===== param 헬퍼 =====
    @SuppressWarnings("unchecked")
    private List<String> packages(Map<String, Object> p) {
        Object v = (p != null) ? p.get("target_packages") : null;
        return (v instanceof List<?> l) ? (List<String>) l : List.of();
    }
    private String timeWindow(Map<String, Object> p) {
        Object v = (p != null) ? p.get("time_window") : null;
        return (v != null) ? v.toString() : null;
    }
    private Double dbl(Map<String, Object> p, String key) {
        Object v = (p != null) ? p.get(key) : null;
        if (v instanceof Number n) return n.doubleValue();
        try { return (v != null) ? Double.parseDouble(v.toString().trim()) : null; } catch (Exception e) { return null; }
    }
    private String strParam(Map<String, Object> p, String key) {
        Object v = (p != null) ? p.get(key) : null;
        return (v != null) ? v.toString() : null;
    }
    private String timeParam(Map<String, Object> p, String key, String def) {
        String v = strParam(p, key);
        return (v != null) ? v : def;
    }
    private String timeParamOrNull(Map<String, Object> p, String key) { return strParam(p, key); }
    private int intParam(Map<String, Object> p, String key, int def) {
        Object v = (p != null) ? p.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        try { return (v != null) ? Integer.parseInt(v.toString().trim()) : def; } catch (Exception e) { return def; }
    }
    private BigDecimal decimalParam(Map<String, Object> p, String key, BigDecimal def) {
        BigDecimal v = decimalParamOrNull(p, key);
        return (v != null) ? v : def;
    }
    private BigDecimal decimalParamOrNull(Map<String, Object> p, String key) {
        Object v = (p != null) ? p.get(key) : null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return (v != null) ? new BigDecimal(v.toString().trim()) : null; } catch (Exception e) { return null; }
    }
}
