package com.ruleup.ruleup_backend.challenge.dto;

/** 3.1 추천 요청. 제목(필수, ≤30) + 설명(선택, ≤200). */
public record RecommendationRequest(String title, String description) {}