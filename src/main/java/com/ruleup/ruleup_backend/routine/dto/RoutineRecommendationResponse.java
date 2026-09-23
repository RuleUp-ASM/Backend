package com.ruleup.ruleup_backend.routine.dto;

import java.util.List;

/**
 * 1단계 추천 응답.
 *  - matched           : 자동 인증 가능 루틴에 매칭됐는지. false = 자동 인증 불가 → 체크형 수동 1개만.
 *  - templateId        : 매칭된 템플릿 id(없으면 null)
 *  - category          : 매칭된 루틴 카테고리(없으면 null)
 *  - recommendedMethod : 기본 선택(매칭=AUTO, 미매칭=MANUAL)
 *  - options           : 인증 방식 후보(매칭 시 자동+수동 2개, 미매칭 시 체크형 수동 1개 — AUTO 없음)
 *  - params            : 사용자가 수정할 목표값(범위 포함). 없으면 빈 배열.
 *  - rationale         : 매칭 시 자동 인증 동작 설명(템플릿 rationale), 미매칭 시 "자동 인증 불가" 안내 문구.
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