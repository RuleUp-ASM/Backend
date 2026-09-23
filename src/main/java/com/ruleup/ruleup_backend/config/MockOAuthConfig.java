package com.ruleup.ruleup_backend.config;

import com.ruleup.ruleup_backend.oauth.MockOAuthClient;
import com.ruleup.ruleup_backend.oauth.OAuthClient;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * local / test 프로파일에서만 가짜 OAuth 클라이언트(KAKAO·GOOGLE)를 등록한다.
 * 이때 실제 KakaoOAuthClient/GoogleOAuthClient는 @Profile("!local & !test")로 꺼지므로
 * provider별 빈이 정확히 하나씩만 남아 OAuthClientResolver가 충돌하지 않는다.
 */
@Profile({"local", "test"})
@Configuration
public class MockOAuthConfig {

    @Bean
    public OAuthClient mockKakaoClient() {
        return new MockOAuthClient(OAuthProvider.KAKAO);
    }

    @Bean
    public OAuthClient mockGoogleClient() {
        return new MockOAuthClient(OAuthProvider.GOOGLE);
    }
}