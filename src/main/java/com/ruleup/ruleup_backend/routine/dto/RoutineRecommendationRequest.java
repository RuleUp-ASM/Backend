package com.ruleup.ruleup_backend.routine.dto;

/**
 * 1단계 추천 요청. 제목(필수) + 설명(선택)만.
 *
 * 추천 단계에서는 권한을 보지 않는다 — 템플릿 매칭과 "이 루틴이 어떤 인증을 지원하는지"만 알려준다.
 * 권한 보유 여부 확인/요청은 생성(2단계, RoutineService)에서 한다.
 */
public record RoutineRecommendationRequest(
        String title,
        String description
) {
}