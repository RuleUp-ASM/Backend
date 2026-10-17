package com.ruleup.ruleup_backend.oauth;

import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 부하테스트 전용 로그인 우회 — stg 에서 잠깐 켜고 끈다. prod 프로파일에는 빈 자체가 없다.
 *
 * <p>실제 구글·카카오 인가 코드는 1회용이라 같은 로그인을 수천 번 반복할 수 없다. 이 문은 IdP 호출만 빼고
 * 그 뒤의 실제 로그인 트랜잭션(제재 확인·행 잠금·활동 기록·소셜 토큰·리프레시 토큰 발급)을 그대로 태운다.
 * 조건 두 가지가 모두 맞을 때만 열린다.
 * <ul>
 *   <li>{@code code} 가 {@value #CODE_PREFIX} 로 시작 — 나머지가 가짜 사용자의 식별자다</li>
 *   <li>{@code codeVerifier} 가 stg 전용 {@code DEV_TOKEN_SECRET} 과 같다 — 비밀값은 저장되지 않는다</li>
 * </ul>
 * 그 밖의 요청은 평소처럼 실제 IdP 로 간다(켜 둔 동안에도 앱의 실제 로그인은 그대로 된다).
 */
@Slf4j
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "app.oauth.load-test.enabled", havingValue = "true")
public class LoadTestOAuthGate {

    public static final String CODE_PREFIX = "loadtest-";

    private final byte[] secret;

    public LoadTestOAuthGate(@Value("${app.dev-tokens.secret:}") String secret) {
        if (secret == null || secret.isBlank())
            throw new IllegalStateException("app.oauth.load-test.enabled 는 DEV_TOKEN_SECRET 이 있어야 켤 수 있다");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        log.warn("load_test_oauth_enabled — 부하테스트용 로그인 우회가 켜져 있다. 테스트가 끝나면 끈다");
    }

    public boolean admits(String code, String codeVerifier) {
        return code != null && code.startsWith(CODE_PREFIX) && code.length() > CODE_PREFIX.length() && codeVerifier != null
                && MessageDigest.isEqual(secret, codeVerifier.getBytes(StandardCharsets.UTF_8));
    }

    public OAuthClient client(OAuthProvider provider) {
        return new MockOAuthClient(provider);
    }
}
