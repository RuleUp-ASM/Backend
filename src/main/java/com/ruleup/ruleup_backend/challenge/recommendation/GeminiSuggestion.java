package com.ruleup.ruleup_backend.challenge.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Gemini가 JSON으로 돌려주는 "날것"의 추천값.
 * enum/숫자는 LLM이 틀릴 수 있으므로 전부 느슨하게 받고(String 등),
 * 서버(ChallengeRecommendationService)가 검증·보정한다 (스펙 2.2 / 5 신뢰 경계).
 * 모르는 필드가 와도 무시(@JsonIgnoreProperties).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiSuggestion(
        String refinedTitle,
        String description,
        String category,
        String participationType,
        BigDecimal minMannerTemperature,
        List<String> repeatDays,
        Integer durationDays,
        List<String> verificationMethods,
        BigDecimal mannerDeduction,
        Boolean snsShare,
        Boolean groupShare,
        BigDecimal mannerGain
) {}