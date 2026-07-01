package com.ruleup.ruleup_backend.auth.dto;

import java.util.List;

/**
 * POST /api/v1/auth/signup 요청(스펙 4.3). 약관은 clientProperties.agreements 안에 boolean 으로 온다.
 * 동의 버전은 클라가 보내지 않으므로 서버 기준값(DEFAULT_AGREEMENT_VERSION)으로 저장한다.
 */
public record SignupRequest(
        String signupToken,
        String nickname,
        List<String> interestCategories,
        String profileImageUrl,
        ClientProperties clientProperties,
        DeviceInfoRequest deviceInfo) {

    public record ClientProperties(Agreements agreements) {}

    /** 약관 동의(boolean). terms·privacy 필수 true, marketing 선택. */
    public record Agreements(Boolean terms, Boolean privacy, Boolean marketing) {}

    /** clientProperties 안의 agreements 안전 추출(미전송이면 null). */
    public Agreements agreements() {
        return (clientProperties != null) ? clientProperties.agreements() : null;
    }
}
