package com.ruleup.ruleup_backend.verification.service;

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
 * 챌린지 저장값(스냅샷 + params + templateId)으로 §4.3 rich VerificationConfig를 조립.
 *  - method 라우팅: RoutineTemplate.verificationMethod(명시 컬럼, V7) 우선 — 캐시(RoutineCatalog)로 조회해 DB 무부하.
 *  - 명시 컬럼이 없으면(직접 입력 등) 휴리스틱 fallback.
 *  - tag 값: WAKE / SCREEN_TIME_MIN / SCREEN_TIME_MAX / GPS_PRESENCE / GPS_DISTANCE / SLEEP / PHOTO / SELF_CHECK
 */
@Component
@RequiredArgsConstructor
public class VerificationConfigFactory {

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
        SleepConfig sleep = null;

        switch (tag) {
            case "WAKE" -> wake = new WakeConfig(
                    timeParam(params, "target_time", "07:00"), Polarity.ACHIEVEMENT, 2);
            case "SCREEN_TIME_MIN" -> screenTime = new ScreenTimeConfig(
                    ScreenTimeMode.MIN, Polarity.ACHIEVEMENT,
                    packages(params), intParam(params, "duration_min", 30), timeWindow(params), null, 1);
            case "SCREEN_TIME_MAX" -> screenTime = new ScreenTimeConfig(
                    ScreenTimeMode.MAX, Polarity.CONSTRAINT,
                    packages(params), intParam(params, "duration_min", 30), timeWindow(params), null, 1);
            case "GPS_PRESENCE" -> gps = new GpsConfig(
                    GpsMode.PRESENCE, dbl(params, "lat"), dbl(params, "lng"),
                    intParam(params, "radius_m", 100), intParam(params, "duration_min", 60),
                    intParam(params, "duration_min", 60), null, timeWindow(params), null,
                    Polarity.ACHIEVEMENT, 1, 100, 50, List.of());
            case "GPS_DISTANCE" -> gps = new GpsConfig(
                    GpsMode.DISTANCE, null, null, null, null, null,
                    decimalParam(params, "distance_km", BigDecimal.valueOf(3)), null, null,
                    Polarity.ACHIEVEMENT, 1, 100, 50, List.of());
            case "SLEEP" -> sleep = new SleepConfig(
                    null, decimalParam(params, "sleep_hours", BigDecimal.valueOf(7)), Polarity.ACHIEVEMENT, 12);
            default -> { /* PHOTO / SELF_CHECK: 수동 */ }
        }

        return new VerificationConfig(
                scheduleType, frequency, MethodCombine.AND, List.of(method),
                gps, screenTime, wake, sleep, perms);
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
            case "GPS_PRESENCE" -> VerificationMethod.GPS_PRESENCE;
            case "GPS_DISTANCE" -> VerificationMethod.GPS_DISTANCE;
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
            case GPS -> (params != null && params.containsKey("distance_km")) ? "GPS_DISTANCE" : "GPS_PRESENCE";
            case ACTIVITY -> "GPS_DISTANCE";
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
    private String timeParam(Map<String, Object> p, String key, String def) {
        Object v = (p != null) ? p.get(key) : null;
        return (v != null) ? v.toString() : def;
    }
    private int intParam(Map<String, Object> p, String key, int def) {
        Object v = (p != null) ? p.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        try { return (v != null) ? Integer.parseInt(v.toString().trim()) : def; } catch (Exception e) { return def; }
    }
    private BigDecimal decimalParam(Map<String, Object> p, String key, BigDecimal def) {
        Object v = (p != null) ? p.get(key) : null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return (v != null) ? new BigDecimal(v.toString().trim()) : def; } catch (Exception e) { return def; }
    }
}
