package com.ruleup.ruleup_backend.routine.dto;

import com.ruleup.ruleup_backend.routine.domain.ParamSpec;

import java.math.BigDecimal;

/**
 * 사용자가 수정 가능한 목표값 1개(추천 응답용).
 *  - value        : 추천 초기값(LLM 이 뽑은 값 또는 템플릿 기본값)
 *  - defaultValue : 템플릿 기본값(되돌리기용)
 *  - kind         : NUMBER / TIME (UI 입력기 분기용)
 *  - min/max      : 숫자형의 설정 가능 범위(없으면 null = 양수만)
 */
public record RoutineParam(
        String key,
        Object value,
        Object defaultValue,
        String kind,
        String unit,
        BigDecimal min,
        BigDecimal max
) {
    public static RoutineParam of(ParamSpec spec, Object value) {
        return new RoutineParam(
                spec.key(), value, spec.defaultValue(),
                spec.kind().name(), spec.unit(), spec.min(), spec.max());
    }
}