package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * PUT /api/v1/challenges/{challengeId}/my-location 응답.
 *
 * @param anchors               적용된 앵커 세트(요청과 동일한 구조)
 * @param serverRadiusM         서버 설정 반경(m)
 * @param appliedFrom           "IMMEDIATE" 고정. 인증 윈도우 중이면 애초에 409로 거부되므로 지연 적용 개념이 없다
 * @param nextChangeAvailableAt 이번 저장으로 월 1회가 소진되므로 항상 다음 달 1일 00:00 KST
 */
public record MemberLocationUpdateResponse(
        List<AnchorDto> anchors,
        Integer serverRadiusM,
        String appliedFrom,
        String nextChangeAvailableAt
) {}
