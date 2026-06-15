package com.ruleup.ruleup_backend.routine.dto;

import java.util.List;

/**
 * 1단계 추천 응답.
 *  - matched           : 템플릿에 매칭됐는지(false 면 직접 입력 = 수동 1개만)
 *  - templateId        : 매칭된 템플릿 id(없으면 null)
 *  - category          : 매칭된 루틴 카테고리(없으면 null)
 *  - recommendedMethod : 기본 선택(AUTO/MANUAL)
 *  - options           : 인증 방식 후보(자동+수동 최대 2개, 매칭 실패 시 수동 1개)
 *  - params            : 사용자가 수정할 목표값(범위 포함). 없으면 빈 배열.
 *  - rationale         : 자동 인증 동작 방식 한 줄 설명(템플릿 rationale). 없으면 null.
 *
 * 상태 저장 없음 — 사용자가 방식/목표값을 고쳐 생성 API(2단계)로 보낸다.
 */
public record RoutineRecommendationResponse(
        boolean matched,
        Long templateId,
        String title,
        String category,
        String recommendedMethod,
        List<RoutineOption> options,
        List<RoutineParam> params,
        String rationale
) {
}