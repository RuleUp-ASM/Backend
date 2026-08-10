package com.ruleup.ruleup_backend.challenge.dto;

import java.util.List;

/**
 * GET /api/v1/challenges/recommendations 응답 — "지금 시작하기 좋은 루틴".
 * 어떤 경우에도 3개 보장(진행 중 카테고리 제외보다 3개 보장이 우선).
 */
public record CreationRecommendationsResponse(List<Item> items) {

    public record Item(
            Long templateId,          // by-template 호출에 사용
            String title,             // 루틴명(검증된 값 — 모더레이션 면제)
            String description,       // 설명(없으면 null)
            String category,          // 12종 enum
            String verificationType,  // AUTO — 루틴 테이블엔 자동 인증 가능 루틴만
            String reason             // 추천 사유 표시 문구
    ) {}
}
