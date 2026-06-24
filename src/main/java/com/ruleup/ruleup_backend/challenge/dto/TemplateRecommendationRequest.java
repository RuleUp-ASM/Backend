package com.ruleup.ruleup_backend.challenge.dto;

/**
 * 추천 선택 → LLM 우회 추천 요청(Path A). 추천 버튼에서 받은 templateId로 상세를 바로 만든다.
 *  - title/description: 추천에서 자동입력된 값(또는 사용자가 약간 손본 값). 없으면 템플릿 기본.
 */
public record TemplateRecommendationRequest(Long templateId, String title, String description) {}
