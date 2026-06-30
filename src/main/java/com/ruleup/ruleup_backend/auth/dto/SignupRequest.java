package com.ruleup.ruleup_backend.auth.dto;

import java.util.List;

/**
 * POST /api/v1/auth/signup 요청 바디 (스펙 4.3).
 * 명세 구조에 맞춰 약관(agreements)은 clientProperties 안에 중첩된다.
 *
 * {
 *   "signupToken": "...",
 *   "nickname": "성은",
 *   "interestCategories": ["EXERCISE","CODING"],
 *   "profileImageUrl": null,
 *   "clientProperties": {
 *     "agreements": { "terms": true, "privacy": true, "marketing": false },
 *     "deviceInfo": { "platform": "ANDROID", "appVersionCode": 12, "appVersionName": "1.2.0" }
 *   }
 * }
 */
public record SignupRequest(
        String signupToken,
        String nickname,
        List<String> interestCategories,
        String profileImageUrl,
        ClientProperties clientProperties) {

    /** 기기 정보(deviceInfo)는 선택값. 가입 시 최초 수집되어 추천 PLATFORM 세그먼트로 사용된다. */
    public record ClientProperties(Agreements agreements, DeviceInfoRequest deviceInfo) {}

    /** terms·privacy 필수, marketing 선택 */
    public record Agreements(boolean terms, boolean privacy, boolean marketing) {}

    /** clientProperties.agreements 를 null 안전하게 꺼내는 헬퍼 */
    public Agreements agreementsOrNull() {
        return (clientProperties != null) ? clientProperties.agreements() : null;
    }

    /** clientProperties.deviceInfo 를 null 안전하게 꺼내는 헬퍼 */
    public DeviceInfoRequest deviceInfoOrNull() {
        return (clientProperties != null) ? clientProperties.deviceInfo() : null;
    }
}