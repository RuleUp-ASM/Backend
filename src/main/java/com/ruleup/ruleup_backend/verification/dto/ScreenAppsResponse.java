package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * GET /api/v1/challenges/{challengeId}/my-screen-apps 응답 — 앱 셋업/수정 화면 재진입 시 목록 복원용.
 *
 * <p>대상 앱 교체는 항상 익일 00:00부터 적용되는 구조라, 현재 적용 중인 세트({@code apps})와
 * 익일부터 적용될 대기 세트({@code pending})를 함께 내려준다.
 *
 * @param apps                  현재 적용 중인 측정 대상 앱 목록(1개 이상)
 * @param appliedFrom           현재 세트의 적용 시작 시각(ISO-8601, KST)
 * @param pending               익일 적용 대기 중인 변경(없으면 null)
 * @param changeAvailable       이번 달 변경 가능 여부 — 수정 버튼 활성/비활성용
 * @param nextChangeAvailableAt 변경 권한 소진 시 다음 변경 가능 시각. changeAvailable이 true면 null
 */
public record ScreenAppsResponse(
        List<AppDto> apps,
        String appliedFrom,
        Pending pending,
        boolean changeAvailable,
        String nextChangeAvailableAt
) {
    /** 익일 적용 대기 세트. effectiveFrom = 적용 시작 시각(익일 00:00, ISO-8601). */
    public record Pending(List<AppDto> apps, String effectiveFrom) {}
}
