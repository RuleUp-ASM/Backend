package com.ruleup.ruleup_backend.oauth;

import com.ruleup.ruleup_backend.user.OAuthProvider;

/**
 * IdP별 OAuth 처리 추상화.
 * authorization_code(+PKCE)를 토큰으로 교환하고, 사용자 정보를 표준 형태로 돌려준다.
 */
public interface OAuthClient {
    OAuthProvider provider();
    OAuthUserInfo fetchUserInfo(String code, String codeVerifier, String redirectUri);
}