package com.ruleup.ruleup_backend.oauth;

import com.ruleup.ruleup_backend.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 카카오 토큰 교환 요청 조립 검증.
 *
 * <p>계약상 {@code redirectUri} 는 null 일 수 있다(카카오톡 간편 로그인 등 SDK 내부 처리).
 * 하지만 카카오 토큰 엔드포인트는 인가 요청에 쓴 {@code redirect_uri} 와의 일치를 요구하므로,
 * null 을 그대로 실어보내면 실연동에서 KOE006 으로 깨진다 —
 * 통합 테스트의 Mock IdP 는 redirectUri 를 쓰지 않아 이 구멍을 잡지 못한다.
 * 그래서 서버가 설정값({@code app.oauth.kakao.redirect-uri})으로 채우는지 여기서 직접 확인한다.
 */
class KakaoOAuthClientTest {

    private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String USER_URI = "https://kapi.kakao.com/v2/user/me";
    private static final String CONFIGURED_REDIRECT_URI = "kakao1a2b3c://oauth";

    private static final String TOKEN_RESPONSE = """
            {"access_token":"at","refresh_token":"rt","expires_in":21599}""";
    private static final String USER_RESPONSE = """
            {"id":12345,"kakao_account":{"email":"a@b.c","profile":{"nickname":"도전왕"}}}""";

    private MockRestServiceServer server;
    private KakaoOAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoOAuthClient(props(), builder.build());
    }

    private AppProperties props() {
        AppProperties.Oauth.Provider kakao =
                new AppProperties.Oauth.Provider("client-id", "client-secret", CONFIGURED_REDIRECT_URI);
        return new AppProperties(null, new AppProperties.Oauth(kakao, null, null), null, null, null);
    }

    private void expectExchange(String expectedRedirectUri) {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("redirect_uri=" + expectedRedirectUri
                                .replace(":", "%3A").replace("/", "%2F"))))
                .andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URI))
                .andRespond(withSuccess(USER_RESPONSE, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("redirectUri 가 null 이면 서버 설정값으로 채워 보낸다 — 계약(null 허용)과 실연동을 모두 만족")
    void fallsBackToConfiguredRedirectUri() {
        expectExchange(CONFIGURED_REDIRECT_URI);

        OAuthUserInfo info = client.fetchUserInfo("code", "verifier", null);

        server.verify();
        assertThat(info.subject()).isEqualTo("12345");
        assertThat(info.nickname()).isEqualTo("도전왕");
    }

    @Test
    @DisplayName("클라가 redirectUri 를 보내면 그 값을 그대로 쓴다")
    void usesClientProvidedRedirectUri() {
        String fromClient = "kakaozzzz://oauth";
        expectExchange(fromClient);

        client.fetchUserInfo("code", "verifier", fromClient);

        server.verify();
    }

    @Test
    @DisplayName("공백 문자열도 누락으로 보고 설정값으로 대체한다")
    void treatsBlankAsMissing() {
        expectExchange(CONFIGURED_REDIRECT_URI);

        client.fetchUserInfo("code", "verifier", "   ");

        server.verify();
    }
}
