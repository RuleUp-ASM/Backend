package com.ruleup.ruleup_backend.recommendation.dto;

/**
 * 추천 루틴 1건(생성화면 추천 버튼). 탭하면 title/description 자동입력 → templateId로 상세(LLM 우회).
 *  - title       : 자동입력될 제목(= 템플릿 이름)
 *  - description : 자동입력될 설명(템플릿 설명, 없으면 null)
 *  - reason      : 추천 근거(POPULAR_IN_SEGMENT / INTEREST_MATCH / EXPLORE)
 */
public record RecommendedRoutine(
        Long templateId,
        String title,
        String description,
        String category,
        String reason
) {}
