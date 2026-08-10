package com.ruleup.ruleup_backend.challenge.dto;

/** POST /api/v1/challenges/recommendation/by-template 요청 — 선택한 루틴 템플릿 id. */
public record TemplateRecommendationRequest(Long templateId) {}
