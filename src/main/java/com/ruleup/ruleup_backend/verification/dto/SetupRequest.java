package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * §11.4 최초 진입 셋업. config.method가 요구하는 바인딩만 전송(§5.6: 권한은 받지 않는다).
 *  - location: GPS일 때 {fillMode, anchors[]}.
 *  - screenApps: SCREEN_TIME일 때 사용자가 고른 대상 앱 {packageName, appName}(권장).
 *  - targetPackages: (레거시) 패키지명 문자열 목록. screenApps 미전송 시 폴백(appName 스냅샷 없음).
 */
public record SetupRequest(
        LocationBinding location,
        List<ScreenAppsUpdateRequest.AppDto> screenApps,
        List<String> targetPackages
) {
    public record LocationBinding(String fillMode, List<AnchorDto> anchors) {}
    public record AnchorDto(double lat, double lng, int radiusM, String label) {}
}
