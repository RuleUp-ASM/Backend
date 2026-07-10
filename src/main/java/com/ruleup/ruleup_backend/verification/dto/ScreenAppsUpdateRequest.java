package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * PUT /api/v1/challenges/{challengeId}/my-screen-apps 요청.
 * apps: { packageName, appName } (1~10개, packageName 중복 불가). 항상 익일 00:00부터 적용.
 */
public record ScreenAppsUpdateRequest(List<AppDto> apps) {
    public record AppDto(String packageName, String appName) {}
}
