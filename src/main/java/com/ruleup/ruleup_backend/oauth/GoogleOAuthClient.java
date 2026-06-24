package com.ruleup.ruleup_backend.oauth;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.user.OAuthProvider;
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
            String accessToken = requestToken(code, codeVerifier, redirectUri);
            GoogleUserResponse u = restClient.get().uri(USER_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve().body(GoogleUserResponse.class);
            // 200이지만 바디가 비었거나 역직렬화 실패 시 body()가 null → NPE 누수 방지
            if (u == null || u.sub() == null)
                throw new BusinessException(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE);
            return new OAuthUserInfo(u.sub(), u.email(), u.picture());
        } catch (RestClientResponseException e) {
            log.warn("Google OAuth failed: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(e.getStatusCode().is4xxClientError()
                    ? ErrorCode.LOGIN_FAILED : ErrorCode.LOGIN_PROVIDER_UNAVAILABLE);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE);
        }
    }

    private String requestToken(String code, String codeVerifier, String redirectUri) {
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
            throw new BusinessException(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE);
        return res.accessToken();
    }

    record GoogleTokenResponse(@JsonProperty("access_token") String accessToken) {}
    record GoogleUserResponse(String sub, String email, String picture) {}
}