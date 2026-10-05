package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * PUT /api/v1/challenges/{challengeId}/my-location 응답.
 *
 * @param anchors               저장된 앵커 세트(요청과 동일한 구조)
 * @param serverRadiusM         서버 설정 반경(m)
 * @param appliedFrom           평상시엔 "IMMEDIATE". 인증 윈도우 중에 바꿨으면 다음 날 00:00 KST(ISO-8601) —
 *                              오늘 판정은 이전 장소로 하고 새 장소는 그때부터 쓴다
 * @param nextChangeAvailableAt 이번 저장으로 월 1회가 소진되므로 항상 다음 달 1일 00:00 KST
 */
public record MemberLocationUpdateResponse(
        List<AnchorDto> anchors,
        Integer serverRadiusM,
        String appliedFrom,
        String nextChangeAvailableAt
) {}
