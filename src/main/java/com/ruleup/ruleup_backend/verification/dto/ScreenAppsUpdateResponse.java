package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * PUT /api/v1/challenges/{challengeId}/my-screen-apps 응답.
 *
 * @param apps                  접수된 앱 세트. 변경은 익일부터 적용되므로 조회 API에서는 pending으로 보인다.
 *                              적용 중인 세트가 없던 첫 설정은 즉시 적용된다
 * @param appliedFrom           적용 시작 시각(ISO-8601, KST) — 변경은 익일 00:00, 첫 설정은 저장 시각
 * @param nextChangeAvailableAt 다음 변경 가능 시각. 변경이면 다음 달 1일 00:00 KST, 한도를 쓰지 않은
 *                              첫 설정·동일 값 재저장은 이전 변경 기준(없으면 null)
 */
public record ScreenAppsUpdateResponse(
        List<AppDto> apps,
        String appliedFrom,
        String nextChangeAvailableAt
) {}
