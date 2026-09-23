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

    private final RestClient restClient;   // 공용 풀링 빈(OAuthHttpClientConfig) 주입
    private final AppProperties.Oauth.Provider config;

    public KakaoOAuthClient(AppProperties props, RestClient oauthRestClient) {
        this.config = props.oauth().kakao();
        this.restClient = oauthRestClient;
    }

    @Override
    public OAuthProvider provider() { return OAuthProvider.KAKAO; }

    @Override
    public OAuthUserInfo fetchUserInfo(String code, String codeVerifier, String redirectUri) {
        try {
            KakaoTokenResponse token = requestToken(code, codeVerifier, redirectUri);
            return requestUser(token);
        } catch (RestClientResponseException e) {
            // 카카오가 준 에러 바디(error, error_code: 예 KOE320)를 함께 남겨야 원인 파악 가능.
            // (status만으로는 redirect_uri 불일치 / PKCE / client_secret 중 무엇인지 알 수 없음)
            log.warn("Kakao OAuth failed: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            // 4xx = 우리가 보낸 인가코드가 거절된 것(만료·재사용·PKCE 불일치) → 사용자는 다시 로그인하면 된다.
            // 5xx = 카카오 쪽 장애 → 사용자가 할 수 있는 건 기다리는 것뿐이다. 안내가 서로 달라야 한다.
            throw e.getStatusCode().is4xxClientError()
                    ? BusinessException.withMessage(ErrorCode.LOGIN_FAILED, "IDP_REJECTED",
                            "로그인 확인이 만료됐어요. 다시 로그인해주세요.")
                    : BusinessException.withMessage(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE, "IDP_ERROR",
                            "소셜 로그인 서버에 문제가 있어요. 잠시 후 다시 시도해주세요.");
        } catch (RestClientException e) {                    // 연결 실패·타임아웃 등
            log.warn("Kakao OAuth transport failed: type={}, message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            throw BusinessException.withMessage(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE, "IDP_UNREACHABLE",
                    "소셜 로그인 서버와 연결하지 못했어요. 네트워크를 확인하고 다시 시도해주세요.");
        }
    }

    private KakaoTokenResponse requestToken(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", config.clientId());        // 카카오 REST API 키
        form.add("client_secret", config.clientSecret());
        form.add("redirect_uri", resolveRedirectUri(redirectUri));
        form.add("code", code);
        if (codeVerifier != null && !codeVerifier.isBlank()) form.add("code_verifier", codeVerifier);


        KakaoTokenResponse res = restClient.post().uri(TOKEN_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(KakaoTokenResponse.class);
        if (res == null || res.accessToken() == null)
            throw BusinessException.withMessage(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE, "IDP_BAD_RESPONSE",
                    "소셜 로그인 서버 응답을 읽지 못했어요. 잠시 후 다시 시도해주세요.");
        return res;
    }

    /**
     * 계약상 redirectUri 는 null 일 수 있다(카카오톡 간편 로그인 등 SDK 내부 처리).
     * 그런데 카카오 토큰 엔드포인트는 인가 요청에 쓴 값과의 일치를 요구하므로 빈 값을 보내면
     * KOE006 으로 실패한다 → 누락 시 서버 설정값(등록된 redirect_uri)으로 채운다.
     */
    private String resolveRedirectUri(String fromClient) {
        if (fromClient != null && !fromClient.isBlank()) return fromClient;
        return config.redirectUri();
    }

    private OAuthUserInfo requestUser(KakaoTokenResponse token) {
        KakaoUserResponse res = restClient.get().uri(USER_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                .retrieve().body(KakaoUserResponse.class);
        // 200이지만 바디가 비었거나 역직렬화 실패 시 body()가 null → NPE 누수 방지
        if (res == null || res.id() == null)
            throw BusinessException.withMessage(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE, "IDP_BAD_RESPONSE",
                    "소셜 계정에서 회원 정보를 받지 못했어요. 잠시 후 다시 시도해주세요.");

        String email = (res.kakaoAccount() != null) ? res.kakaoAccount().email() : null;
        String nickname = (res.kakaoAccount() != null && res.kakaoAccount().profile() != null)
                ? res.kakaoAccount().profile().nickname() : null;
        String img = (res.kakaoAccount() != null && res.kakaoAccount().profile() != null)
                ? res.kakaoAccount().profile().profileImageUrl() : null;
        java.time.Instant expiresAt = (token.expiresIn() != null)
                ? java.time.Instant.now().plusSeconds(token.expiresIn()) : null;
        return new OAuthUserInfo(String.valueOf(res.id()), email, nickname, img,
                new OAuthUserInfo.IdpTokens(token.accessToken(), token.refreshToken(), expiresAt));
    }

    // 카카오 응답에서 필요한 필드만 (SNAKE_CASE 설정으로 access_token→accessToken 자동 매핑)
    record KakaoTokenResponse(@JsonProperty("access_token") String accessToken,
                              @JsonProperty("refresh_token") String refreshToken,
                              @JsonProperty("expires_in") Long expiresIn) {}
    record KakaoUserResponse(Long id, @JsonProperty("kakao_account") KakaoAccount kakaoAccount) {
        record KakaoAccount(String email, Profile profile) {
            record Profile(String nickname, @JsonProperty("profile_image_url") String profileImageUrl) {}
        }
    }
}
