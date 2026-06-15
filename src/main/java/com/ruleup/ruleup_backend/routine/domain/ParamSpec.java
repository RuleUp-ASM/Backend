package com.ruleup.ruleup_backend.routine.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 목표 파라미터 1개의 정의(스키마). routine_template.param_schema 의 각 항목을 해석한 값.
 *
 * 예) "distance_km": {"default":3, "unit":"km", "min":1, "max":50}  → NUMBER
 *     "target_time":  {"default":"07:00", "unit":"hh:mm"}            → TIME
 *
 * 시드에는 min/max 가 없을 수 있다 → 그 경우 NUMBER 는 "양수"만 확인한다.
 * 값 검증은 두 갈래:
 *   - clampOrDefault : LLM 이 뽑은 값(신뢰 못 함) → 범위 벗어나면 기본값으로 대체
 *   - validate       : 사용자가 보낸 값(피드백 줘야 함) → 잘못되면 예외
 */
public record ParamSpec(
        String key,
        Kind kind,
        Object defaultValue,   // NUMBER=BigDecimal, TIME=String, 없으면 null
        String unit,
        BigDecimal min,        // NUMBER 전용, 없으면 null
        BigDecimal max         // NUMBER 전용, 없으면 null
) {
    public enum Kind { NUMBER, TIME }

    private static final Pattern TIME = Pattern.compile("^([01]?\\d|2[0-3]):[0-5]\\d$");

    /** param_schema 의 한 항목(map)을 ParamSpec 으로 해석. */
    public static ParamSpec parse(String key, Map<String, Object> spec) {
        Object def = spec.get("default");
        Object unitObj = spec.get("unit");
        String unit = (unitObj != null) ? unitObj.toString() : null;

        boolean isTime = "hh:mm".equalsIgnoreCase(unit)
                || (def instanceof String s && TIME.matcher(s).matches());

        if (isTime) {
            String defStr = (def != null) ? def.toString() : null;
            return new ParamSpec(key, Kind.TIME, defStr, unit, null, null);
        }
        return new ParamSpec(key, Kind.NUMBER, toBigDecimal(def), unit,
                toBigDecimal(spec.get("min")), toBigDecimal(spec.get("max")));
    }

    /** LLM 값 보정: 유효하면 그 값, 아니면 기본값(없으면 null). 예외 던지지 않음. */
    public Object clampOrDefault(Object raw) {
        try {
            return (raw != null) ? validate(raw) : defaultValue;
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    /** 사용자 값 검증: 유효하면 정규화한 값 반환, 아니면 IllegalArgumentException. */
    public Object validate(Object raw) {
        if (kind == Kind.TIME) {
            String s = String.valueOf(raw);
            if (!TIME.matcher(s).matches())
                throw new IllegalArgumentException(key + ": 시간 형식(HH:mm)이 아닙니다.");
            return s;
        }
        BigDecimal v = toBigDecimal(raw);
        if (v == null || v.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException(key + ": 0보다 큰 숫자여야 합니다.");
        if (min != null && v.compareTo(min) < 0)
            throw new IllegalArgumentException(key + ": 최소 " + min + " 이상이어야 합니다.");
        if (max != null && v.compareTo(max) > 0)
            throw new IllegalArgumentException(key + ": 최대 " + max + " 이하여야 합니다.");
        return v;
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}