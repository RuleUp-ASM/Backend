package com.ruleup.ruleup_backend.oauth;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;

/**
 * 로컬/테스트 전용 가짜 OAuth 클라이언트.
 * 실제 카카오/구글에 붙지 않고, 넘어온 code를 그대로 식별자(subject)로 써서
 * 항상 성공하는 가짜 사용자 정보를 돌려준다.
 *
 * 빈 등록은 MockOAuthConfig(@Profile)에서 KAKAO/GOOGLE 두 개로 한다.
 * 같은 code로 두 번 호출하면 같은 subject가 나오므로 "기존 회원"으로 잡힌다.
 *
 * <p>IdP 실패 계약도 테스트할 수 있도록 code 에 표식을 넣으면 실제 클라이언트와 같은 예외를 던진다.
 *  · {@value #FAIL_INVALID_CODE} 포함 → 400 LOGIN_FAILED (인가 코드 검증 실패)
 *  · {@value #FAIL_IDP_DOWN} 포함 → 502 LOGIN_PROVIDER_UNAVAILABLE (IdP 장애·타임아웃)
 */
public class MockOAuthClient implements OAuthClient {

    /** 이 문자열이 code 에 있으면 인가 코드 검증 실패를 흉내 낸다. */
    public static final String FAIL_INVALID_CODE = "fail-invalid";
    /** 이 문자열이 code 에 있으면 IdP 장애(타임아웃·5xx)를 흉내 낸다. */
    public static final String FAIL_IDP_DOWN = "fail-idp-down";

    private final OAuthProvider provider;

    public MockOAuthClient(OAuthProvider provider) {
        this.provider = provider;
    }

    @Override
    public OAuthProvider provider() {
        return provider;
    }

    @Override
    public OAuthUserInfo fetchUserInfo(String code, String codeVerifier, String redirectUri) {
        if (code != null && code.contains(FAIL_IDP_DOWN))
            throw new BusinessException(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE);
        if (code != null && code.contains(FAIL_INVALID_CODE))
            throw new BusinessException(ErrorCode.LOGIN_FAILED);

        String subject = "mock-" + provider.name().toLowerCase() + "-" + code;
        return new OAuthUserInfo(subject, code + "@mock.local", "목유저", null,
                new OAuthUserInfo.IdpTokens("mock-idp-at-" + code, "mock-idp-rt-" + code,
                        java.time.Instant.now().plusSeconds(3600)));
    }
}