package com.ruleup.ruleup_backend.auth.dto;

import java.util.List;

/**
 * POST /api/v1/account/signup 요청 바디 (스펙 4.3).
 * 명세 구조에 맞춰 약관(agreements)은 clientProperties 안에 중첩된다.
 *
 * {
 *   "signupToken": "...",
 *   "nickname": "성은",
 *   "interestCategories": ["EXERCISE","CODING"],
 *   "profileImageUrl": null,
 *   "clientProperties": {
 *     "agreements": { "terms": true, "privacy": true, "marketing": false }
 *   }
 * }
 */
public record SignupRequest(
        String signupToken,
        String nickname,
        List<String> interestCategories,
        String profileImageUrl,
        ClientProperties clientProperties) {

    public record ClientProperties(Agreements agreements) {}

    /** terms·privacy 필수, marketing 선택 */
    public record Agreements(boolean terms, boolean privacy, boolean marketing) {}

    /** clientProperties.agreements 를 null 안전하게 꺼내는 헬퍼 */
    public Agreements agreementsOrNull() {
        return (clientProperties != null) ? clientProperties.agreements() : null;
    }
}