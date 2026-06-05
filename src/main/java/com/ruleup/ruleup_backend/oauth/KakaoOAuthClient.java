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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 OAuth. code+verifier를 kauth로 교환 → kapi에서 사용자 조회.
 * client_id에는 REST API 키, client_secret(콘솔 기본 활성) 함께 전송.
 */
@Profile("!local & !test")   // local/test 환경에서는 MockOAuthClient가 대신 뜬다
@Component
public class KakaoOAuthClient implements OAuthClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoOAuthClient.class);
    private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String USER_URI = "https://kapi.kakao.com/v2/user/me";

    private final RestClient restClient = RestClient.create();
    private final AppProperties.Oauth.Provider config;

    public KakaoOAuthClient(AppProperties props) {
        this.config = props.oauth().kakao();
    }

    @Override
    public OAuthProvider provider() { return OAuthProvider.KAKAO; }

    @Override
    public OAuthUserInfo fetchUserInfo(String code, String codeVerifier, String redirectUri) {
        try {
            String accessToken = requestToken(code, codeVerifier, redirectUri);
            return requestUser(accessToken);
        } catch (RestClientResponseException e) {
            log.warn("Kakao OAuth failed: status={}", e.getStatusCode());
            throw new BusinessException(e.getStatusCode().is4xxClientError()
                    ? ErrorCode.LOGIN_FAILED : ErrorCode.LOGIN_PROVIDER_UNAVAILABLE);
        } catch (RestClientException e) {                    // 연결 실패 등
            throw new BusinessException(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE);
        }
    }

    private String requestToken(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", config.clientId());        // 카카오 REST API 키
        form.add("client_secret", config.clientSecret());
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        if (codeVerifier != null && !codeVerifier.isBlank()) form.add("code_verifier", codeVerifier);


        KakaoTokenResponse res = restClient.post().uri(TOKEN_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(KakaoTokenResponse.class);
        return res.accessToken();
    }

    private OAuthUserInfo requestUser(String accessToken) {
        KakaoUserResponse res = restClient.get().uri(USER_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve().body(KakaoUserResponse.class);

        String email = (res.kakaoAccount() != null) ? res.kakaoAccount().email() : null;
        String img = (res.kakaoAccount() != null && res.kakaoAccount().profile() != null)
                ? res.kakaoAccount().profile().profileImageUrl() : null;
        return new OAuthUserInfo(String.valueOf(res.id()), email, img);
    }

    // 카카오 응답에서 필요한 필드만.
    // RestClient.create()는 전역 Jackson 설정을 따르지 않으므로 @JsonProperty로 직접 매핑한다.
    record KakaoTokenResponse(@JsonProperty("access_token") String accessToken) {}
    record KakaoUserResponse(Long id, @JsonProperty("kakao_account") KakaoAccount kakaoAccount) {
        record KakaoAccount(String email, Profile profile) {
            record Profile(@JsonProperty("profile_image_url") String profileImageUrl) {}
        }
    }
}