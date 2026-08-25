package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * PUT /api/v1/challenges/{challengeId}/my-screen-apps 응답.
 *
 * @param apps                  접수된 앱 세트. 즉시 적용이 아니라 익일부터 적용되므로 조회 API에서는 pending으로 보인다
 * @param appliedFrom           적용 시작 시각(ISO-8601, KST) — 항상 익일 00:00
 * @param nextChangeAvailableAt 이번 저장으로 월 1회가 소진되므로 항상 다음 달 1일 00:00 KST
 */
public record ScreenAppsUpdateResponse(
        List<AppDto> apps,
        String appliedFrom,
        String nextChangeAvailableAt
) {}
