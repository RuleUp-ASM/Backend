package com.ruleup.ruleup_backend.routine.match;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * LLM 매칭 결과(날것). templateId 가 null 이거나 후보에 없으면 "매칭 실패"로 본다.
 * params 의 값/키 유효성은 서버(RoutineRecommendationService)가 param_schema 로 재검증한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoutineMatch(
        Long templateId,
        Map<String, Object> params
) {
    /** 매칭 실패(템플릿 못 찾음) 결과 */
    public static RoutineMatch none() {
        return new RoutineMatch(null, Map.of());
    }

    public boolean matched() {
        return templateId != null;
    }

    public Map<String, Object> paramsOrEmpty() {
        return (params != null) ? params : Map.of();
    }
}
