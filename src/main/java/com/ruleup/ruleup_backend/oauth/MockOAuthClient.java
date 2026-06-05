package com.ruleup.ruleup_backend.oauth;

import com.ruleup.ruleup_backend.user.OAuthProvider;

/**
 * 로컬/테스트 전용 가짜 OAuth 클라이언트.
 * 실제 카카오/구글에 붙지 않고, 넘어온 code를 그대로 식별자(subject)로 써서
 * 항상 성공하는 가짜 사용자 정보를 돌려준다.
 *
 * 빈 등록은 MockOAuthConfig(@Profile)에서 KAKAO/GOOGLE 두 개로 한다.
 * 같은 code로 두 번 호출하면 같은 subject가 나오므로 "기존 회원"으로 잡힌다.
 */
public class MockOAuthClient implements OAuthClient {

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
        String subject = "mock-" + provider.name().toLowerCase() + "-" + code;
        return new OAuthUserInfo(subject, code + "@mock.local", null);
    }
}