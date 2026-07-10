package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * GET /api/v1/challenges/{challengeId}/my-screen-apps 응답.
 * 현재 적용 중인 세트(apps) + 익일부터 적용될 대기 세트(pending)를 함께 반환한다.
 *  - apps        : 현재 적용 중인 앱 목록(1개 이상).
 *  - appliedFrom : 현재 세트 적용 시작 시각(ISO-8601, +09:00).
 *  - pending     : 익일 적용 대기 변경(없으면 null).
 */
public record ScreenAppsResponse(
        List<AppDto> apps,
        String appliedFrom,
        Pending pending
) {
    public record AppDto(String packageName, String appName) {}

    /** 익일 적용 대기 세트. effectiveFrom = 적용 시작 시각(익일 00:00, ISO-8601). */
    public record Pending(List<AppDto> apps, String effectiveFrom) {}
}
