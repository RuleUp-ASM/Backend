package com.ruleup.ruleup_backend.oauth;

import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoadTestOAuthGateTest {

    private final OAuthClient realKakao = new MockOAuthClient(OAuthProvider.KAKAO) {
        @Override public OAuthUserInfo fetchUserInfo(String c, String v, String r) { throw new AssertionError("실제 IdP 로 가면 안 된다"); }
    };

    @Test
    @DisplayName("prod 프로파일에는 빈이 없고, 플래그를 켜야만 등록된다")
    void neverOnProd() {
        assertThat(LoadTestOAuthGate.class.getAnnotation(Profile.class).value()).containsExactly("!prod");
        assertThat(LoadTestOAuthGate.class.getAnnotation(ConditionalOnProperty.class).havingValue()).isEqualTo("true");
    }

    @Test
    @DisplayName("비밀값 없이 켜면 기동에서 실패한다")
    void requiresSecret() {
        assertThatThrownBy(() -> new LoadTestOAuthGate("")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("접두어와 비밀값이 모두 맞을 때만 IdP 를 건너뛰고, 아니면 실제 클라이언트로 간다")
    @SuppressWarnings("unchecked")
    void admitsOnlyWithPrefixAndSecret() {
        ObjectProvider<LoadTestOAuthGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new LoadTestOAuthGate("s3cret"));
        OAuthClientResolver resolver = new OAuthClientResolver(List.of(realKakao), provider);

        assertThat(resolver.resolve(OAuthProvider.KAKAO, "loadtest-42", "s3cret").fetchUserInfo("loadtest-42", "s3cret", null).subject())
                .isEqualTo("mock-kakao-loadtest-42");
        assertThat(resolver.resolve(OAuthProvider.KAKAO, "loadtest-42", "wrong")).isSameAs(realKakao);
        assertThat(resolver.resolve(OAuthProvider.KAKAO, "real-code", "s3cret")).isSameAs(realKakao);
        assertThat(resolver.resolve(OAuthProvider.KAKAO, "loadtest-", "s3cret")).isSameAs(realKakao);
    }

    @Test
    @DisplayName("플래그가 꺼져 있으면(빈 없음) 항상 실제 클라이언트")
    @SuppressWarnings("unchecked")
    void offMeansReal() {
        ObjectProvider<LoadTestOAuthGate> provider = mock(ObjectProvider.class);
        OAuthClientResolver resolver = new OAuthClientResolver(List.of(realKakao), provider);
        assertThat(resolver.resolve(OAuthProvider.KAKAO, "loadtest-42", "s3cret")).isSameAs(realKakao);
    }
}
