package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * PUT /api/v1/challenges/{challengeId}/my-screen-apps 응답.
 *  - apps        : 접수된 앱 세트.
 *  - appliedFrom : 적용 시작 시각(익일 00:00, ISO-8601).
 */
public record ScreenAppsUpdateResponse(
        List<ScreenAppsResponse.AppDto> apps,
        String appliedFrom
) {}
