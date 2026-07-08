package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.user.domain.Platform;

/**
 * 공통 deviceInfo 객체 (계약: 로그인·가입 양쪽 동반). 필드명은 intro 요청바디와 동일하게 맞춘다(§11.1).
 * 로그인·가입마다 최신 1건으로 갱신한다. 누락/형식오류는 INVALID_DEVICE_INFO 로 거부한다.
 *
 * <p>전체 스펙({@code platform / versionCode / versionName / osVersion / sdkInt / deviceModel /
 * manufacturer / lowRam})을 저장한다(로그인 응답에 그대로 되돌려줌). platform 은 추천 PLATFORM 세그먼트로도 사용.
 *
 * <pre>
 * { "platform":"ANDROID", "osVersion":"14", "sdkInt":34, "deviceModel":"SM-S921N",
 *   "manufacturer":"samsung", "lowRam":false, "versionName":"1.0.0", "versionCode":100 }
 * </pre>
 */
public record DeviceInfoRequest(
        String platform,
        String osVersion,
        Integer sdkInt,
        String deviceModel,
        String manufacturer,
        Boolean lowRam,
        String versionName,
        Integer versionCode) {

    /** 문자열 platform 을 enum 으로(대소문자 무시, 알 수 없거나 비면 null). */
    public Platform toPlatform() {
        if (platform == null || platform.isBlank()) return null;
        try {
            return Platform.valueOf(platform.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 계약상 deviceInfo는 로그인·가입에 필수. 최소 필드(platform·versionCode)가 유효해야 한다.
     * 형식 위반 시 호출부에서 INVALID_DEVICE_INFO 로 거부.
     */
    public boolean isValid() {
        return toPlatform() != null && versionCode != null;
    }
}
