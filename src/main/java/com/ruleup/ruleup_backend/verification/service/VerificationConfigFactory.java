package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.domain.SignalSource;
import com.ruleup.ruleup_backend.verification.domain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 챌린지가 저장한 (스냅샷 인증방식 + params 목표값 + 일정)으로 §4.3 rich VerificationConfig를 조립.
 * Challenge 저장 타입은 그대로 두고(approach B) 평가 시점에 파생 — churn 최소.
 *
 * ⚠️ method 매핑은 휴리스틱(signalSource + param 키). 105 템플릿이 7-method 기준으로 태깅돼 있지 않아
 *    일부 오분류 가능(예: USAGE+target_time 취침 vs 기상). 프로덕션 정확성은 RoutineTemplate.verificationMethod
 *    컬럼 명시로 대체 예정(별도 마이그레이션).
 */
@Component
public class VerificationConfigFactory {

    public VerificationConfig build(Challenge challenge) {
        var snap = challenge.getVerificationConfig();
        Map<String, Object> params = challenge.getParams();
        List<String> perms = (snap != null) ? snap.requiredPermissions() : List.of();

        // 일정: 현재 Challenge는 repeatDays(고정요일)만 보유 → FIXED_DAYS. (빈도형 입력은 추후 연결)
        ScheduleType scheduleType = ScheduleType.FIXED_DAYS;
        Frequency frequency = null;

        VerificationMethod method = resolveMethod(snap, params);

        WakeConfig wake = null;
        ScreenTimeConfig screenTime = null;
        GpsConfig gps = null;
        SleepConfig sleep = null;

        switch (method) {
            case WAKE -> wake = new WakeConfig(timeParam(params, "target_time", "07:00"), Polarity.ACHIEVEMENT, 2);
            case SCREEN_TIME -> screenTime = new ScreenTimeConfig(
                    ScreenTimeMode.MIN, Polarity.ACHIEVEMENT,
                    List.of(), intParam(params, "duration_min", 30), null, null, 1);
            case GPS_PRESENCE -> gps = new GpsConfig(
                    GpsMode.PRESENCE, null, null, 100, intParam(params, "duration_min", 60),
                    intParam(params, "duration_min", 60), null, null, null, Polarity.ACHIEVEMENT, 1, 100, 50, List.of());
            case GPS_DISTANCE -> gps = new GpsConfig(
                    GpsMode.DISTANCE, null, null, null, null, null,
                    decimalParam(params, "distance_km", BigDecimal.valueOf(3)), null, null, Polarity.ACHIEVEMENT, 1, 100, 50, List.of());
            case SLEEP -> sleep = new SleepConfig(null, decimalParam(params, "sleep_hours", BigDecimal.valueOf(7)), Polarity.ACHIEVEMENT, 12);
            default -> { /* PHOTO / SELF_CHECK: 수동 — 자동 평가 안 함 */ }
        }

        return new VerificationConfig(
                scheduleType, frequency, MethodCombine.AND, List.of(method),
                gps, screenTime, wake, sleep, perms);
    }

    private VerificationMethod resolveMethod(Object snapObj, Map<String, Object> params) {
        if (snapObj == null) return VerificationMethod.PHOTO;
        var snap = (com.ruleup.ruleup_backend.routine.domain.VerificationConfig) snapObj;
        if (snap.selectedMethod() == SelectedMethod.MANUAL) {
            return snap.signalSource() == SignalSource.PHOTO
                    ? VerificationMethod.PHOTO : VerificationMethod.SELF_CHECK;
        }
        SignalSource src = snap.signalSource();
        if (src == null) return VerificationMethod.PHOTO;
        return switch (src) {
            case GEOFENCE -> VerificationMethod.GPS_PRESENCE;
            case GPS -> params != null && params.containsKey("distance_km")
                    ? VerificationMethod.GPS_DISTANCE : VerificationMethod.GPS_PRESENCE;
            case ACTIVITY -> VerificationMethod.GPS_DISTANCE;
            case SLEEP -> VerificationMethod.SLEEP;
            case USAGE -> params != null && params.containsKey("target_time")
                    ? VerificationMethod.WAKE : VerificationMethod.SCREEN_TIME;
            default -> VerificationMethod.PHOTO;   // APP_FEATURE/HC_RECORD/EXTERNAL_API: MVP 미지원 → 수동 취급
        };
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
