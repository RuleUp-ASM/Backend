package com.ruleup.ruleup_backend.recommendation.dto;

/**
 * 세그먼트별 템플릿 점수 1건(캐시 직렬화용 경량 DTO).
 * 엔티티(TemplateSegmentScore)를 그대로 Redis에 넣지 않고 이 레코드로 캐싱한다
 * (detached 엔티티 캐싱 회피 + JSON 라운드트립 안전).
 */
public record SegmentScoreEntry(long templateId, double score) {}
