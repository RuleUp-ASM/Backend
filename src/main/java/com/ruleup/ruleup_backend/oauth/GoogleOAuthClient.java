package com.ruleup.ruleup_backend.oauth;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 구글 OAuth. code 교환 → userinfo에서 sub/email/picture 조회. */
@Profile("!local & !test")   // local/test 환경에서는 MockOAuthClient가 대신 뜬다
@Component
public class GoogleOAuthClient implements OAuthClient {
    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthClient.class);

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String USER_URI = "https://www.googleapis.com/oauth2/v3/userinfo";

    private final RestClient restClient;   // 공용 풀링 빈(OAuthHttpClientConfig) 주입
    private final AppProperties.Oauth.Provider config;

    public GoogleOAuthClient(AppProperties props, RestClient oauthRestClient) {
        this.config = props.oauth().google();
        this.restClient = oauthRestClient;
    }

    @Override
    public OAuthProvider provider() { return OAuthProvider.GOOGLE; }

    @Override
    public OAuthUserInfo fetchUserInfo(String code, String codeVerifier, String redirectUri) {
        try {
            GoogleTokenResponse token = requestToken(code, codeVerifier, redirectUri);
            GoogleUserResponse u = restClient.get().uri(USER_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                    .retrieve().body(GoogleUserResponse.class);
            // 200이지만 바디가 비었거나 역직렬화 실패 시 body()가 null → NPE 누수 방지
            if (u == null || u.sub() == null)
                throw BusinessException.withMessage(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE, "IDP_BAD_RESPONSE",
                        "소셜 계정에서 회원 정보를 받지 못했어요. 잠시 후 다시 시도해주세요.");
            java.time.Instant expiresAt = (token.expiresIn() != null)
                    ? java.time.Instant.now().plusSeconds(token.expiresIn()) : null;
            return new OAuthUserInfo(u.sub(), u.email(), u.name(), u.picture(),
                    new OAuthUserInfo.IdpTokens(token.accessToken(), token.refreshToken(), expiresAt));
        } catch (RestClientResponseException e) {
            log.warn("Google OAuth failed: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            // 4xx = 인가코드 거절(만료·재사용·PKCE 불일치) → 다시 로그인하면 풀린다.
            // 5xx = 구글 쪽 장애 → 기다리는 것 말고는 없다.
            throw e.getStatusCode().is4xxClientError()
                    ? BusinessException.withMessage(ErrorCode.LOGIN_FAILED, "IDP_REJECTED",
                            "로그인 확인이 만료됐어요. 다시 로그인해주세요.")
                    : BusinessException.withMessage(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE, "IDP_ERROR",
                            "소셜 로그인 서버에 문제가 있어요. 잠시 후 다시 시도해주세요.");
        } catch (RestClientException e) {
            log.warn("Google OAuth transport failed: type={}, message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            throw BusinessException.withMessage(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE, "IDP_UNREACHABLE",
                    "소셜 로그인 서버와 연결하지 못했어요. 네트워크를 확인하고 다시 시도해주세요.");
        }
    }

    private GoogleTokenResponse requestToken(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", config.clientId());
        if (config.clientSecret() != null && !config.clientSecret().isBlank()) {
            form.add("client_secret", config.clientSecret());
        }
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            form.add("code_verifier", codeVerifier);
        }

        GoogleTokenResponse res = restClient.post().uri(TOKEN_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(GoogleTokenResponse.class);
        if (res == null || res.accessToken() == null)
            throw BusinessException.withMessage(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE, "IDP_BAD_RESPONSE",
                    "소셜 로그인 서버 응답을 읽지 못했어요. 잠시 후 다시 시도해주세요.");
        return res;
    }

    record GoogleTokenResponse(@JsonProperty("access_token") String accessToken,
                               @JsonProperty("refresh_token") String refreshToken,
                               @JsonProperty("expires_in") Long expiresIn) {}
    record GoogleUserResponse(String sub, String email, String name, String picture) {}
}
