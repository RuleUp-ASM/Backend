package com.ruleup.ruleup_backend.auth.dto;

import java.util.List;

/**
 * POST /api/v1/auth/signup 요청 바디 (스펙 4.3).
 * 계약대로 agreements·deviceInfo 는 top-level 이며, 각 약관은 {agreed, version} 구조다.
 *
 * {
 *   "signupToken": "...",
 *   "nickname": "성은",
 *   "interestCategories": ["EXERCISE","CODING"],
 *   "profileImageUrl": null,
 *   "agreements": {
 *     "termsOfService": { "agreed": true,  "version": "1.0" },
 *     "privacyPolicy":  { "agreed": true,  "version": "1.0" },
 *     "marketing":      { "agreed": false, "version": "1.0" }
 *   },
 *   "deviceInfo": { "platform":"ANDROID", "versionCode":100, "versionName":"1.0.0", ... }
 * }
 */
public record SignupRequest(
        String signupToken,
        String nickname,
        List<String> interestCategories,
        String profileImageUrl,
        Agreements agreements,
        DeviceInfoRequest deviceInfo) {

    /** 약관 동의 묶음. terms·privacy 필수, marketing 선택. 각 약관은 동의여부 + 버전. */
    public record Agreements(Agreement termsOfService, Agreement privacyPolicy, Agreement marketing) {}

    /** 개별 약관 동의 항목(동의 여부 + 동의한 약관 버전). */
    public record Agreement(boolean agreed, String version) {}
}
