package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.user.domain.Platform;

/**
 * 클라이언트 기기 정보. 가입(signup.clientProperties.deviceInfo) 시 최초 수집,
 * 로그인(login.deviceInfo) 시마다 갱신한다. 전부 선택값(미전송 시 기존값 보존).
 *
 * <p>저장된 platform 은 추천 PLATFORM 세그먼트 축으로 사용된다.
 *
 * <pre>
 * { "platform": "ANDROID", "appVersionCode": 12, "appVersionName": "1.2.0" }
 * </pre>
 */
public record DeviceInfoRequest(String platform, Integer appVersionCode, String appVersionName) {

    /** 문자열 platform 을 enum 으로(대소문자 무시, 알 수 없거나 비면 null). */
    public Platform toPlatform() {
        if (platform == null || platform.isBlank()) return null;
        try {
            return Platform.valueOf(platform.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;   // 알 수 없는 플랫폼은 무시(기존값 보존)
        }
    }
}
