package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.oauth.MockOAuthClient;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * OAuth 로그인 계약 테스트 — 그동안 카카오 성공 경로만 검증돼 비어 있던 구간을 채운다.
 *
 * 커버 범위
 *  1) 구글 로그인 경로: 신규 분기(signupToken)·가입·기존 로그인, 카카오와 계정이 분리되는지
 *     (같은 사람이 구글로 따로 가입하면 별개 계정 — 카카오 로그인 API 계약)
 *  2) 요청 형식: code·codeVerifier 누락 400 LOGIN_FAILED,
 *     구글 redirectUri 불일치 400 INVALID_REDIRECT_URI (카카오는 null 허용)
 *  3) IdP 실패: 인가 코드 검증 실패 400 LOGIN_FAILED, IdP 장애 502 LOGIN_PROVIDER_UNAVAILABLE
 *  4) 알 수 없는 provider 400 LOGIN_FAILED
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OAuthLoginContractIT extends AuthApiSupport {

    /** application-test.yaml 의 google.redirect-uri 와 같아야 한다(서버가 등록값과 대조). */
    private static final String GOOGLE_REDIRECT_URI = "ruleup://login/callback";

    @Autowired WebApplicationContext wac;
    @Autowired UserRepository userRepository;
    @Autowired AppProperties appProperties;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() {
        return mvc;
    }

    /** 구글은 redirectUri 를 등록값과 대조하므로 로그인 바디를 따로 만든다. */
    private Map<String, Object> googleLoginBody(String code, String installationId, String deviceId) {
        Map<String, Object> m = loginBody(code, installationId, deviceId);
        m.put("redirectUri", GOOGLE_REDIRECT_URI);
        return m;
    }

    /** 구글로 가입까지 마친다. */
    private MvcResult googleSignup(String tag, String nickname, String installationId) throws Exception {
        MvcResult login = postJson("/api/v1/auth/oauth/google",
                googleLoginBody(tag, installationId, "dev-" + tag));
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        MvcResult res = postJson("/api/v1/auth/signup",
                signupBody(read(login, "$.data.signupToken"), nickname, installationId, "dev-" + tag));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res;
    }

    private void withdraw(String accessToken) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirmPhrase", "탈퇴할게요");
        MvcResult res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(m))).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
    }

    /** JwtAuthenticationFilter의 실제 만료 예외 경로를 타기 위한 테스트용 ACCESS 토큰. */
    private String expiredAccessToken() {
        Instant now = Instant.now();
        var key = Keys.hmacShaKeyFor(appProperties.jwt().secret().getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(UUID.randomUUID().toString())
                .claim("type", "ACCESS")
                .issuedAt(Date.from(now.minusSeconds(3600)))
                .expiration(Date.from(now.minusSeconds(1)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    @Nested
    @DisplayName("만료 세션 재로그인")
    class ExpiredSessionRelogin {

        @Test
        @DisplayName("만료된 ACCESS 토큰이 Authorization에 남아 있어도 기존 소셜 계정은 즉시 재로그인된다")
        void expired_authorization_does_not_block_oauth_login() throws Exception {
            String tag = uniq("expired");
            MvcResult signedUp = signup(tag, "만료복귀" + seq());
            String userId = read(signedUp, "$.data.user.id");

            MvcResult relogin = mvc.perform(post("/api/v1/auth/oauth/kakao")
                    .header("Authorization", "Bearer " + expiredAccessToken())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(OM.writeValueAsString(
                            loginBody(tag, "inst-" + tag, "dev-" + tag))))
                    .andReturn();

            assertThat(relogin.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(relogin, "$.data.isNewUser")).isFalse();
            assertThat((String) read(relogin, "$.data.user.id")).isEqualTo(userId);
        }
    }

    // ==================================================================
    // 0) 설치 게이트는 provider 에 대칭이다 — 어느 쪽으로 먼저 가입했든 같게 동작해야 한다
    // ==================================================================

    @Nested
    @DisplayName("설치 게이트 — provider 대칭")
    class InstallationGateAcrossProviders {

        @Test
        @DisplayName("구글로 가입·탈퇴한 기기에서 카카오로 가입하려 하면 막고, 구글로 가라고 알려준다")
        void google_first_then_kakao_is_blocked() throws Exception {
            String tag = uniq("g");
            String install = "inst-" + tag;
            withdraw(read(googleSignup(tag, "구글먼저" + seq(), install), "$.data.accessToken"));

            MvcResult kakao = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(uniq("k"), install, "dev-" + tag));
            expectError(kakao, 403, "INSTALLATION_ALREADY_REGISTERED");
            assertThat((String) read(kakao, "$.error.reason")).isEqualTo("GOOGLE");
        }

        @Test
        @DisplayName("카카오로 가입·탈퇴한 기기에서 구글로 가입하려 하면 막고, 카카오로 가라고 알려준다")
        void kakao_first_then_google_is_blocked() throws Exception {
            String tag = uniq("k");
            String install = "inst-" + tag;
            withdraw(read(signup(tag, "카카오먼저" + seq()), "$.data.accessToken"));

            MvcResult google = postJson("/api/v1/auth/oauth/google",
                    googleLoginBody(uniq("g"), install, "dev-" + tag));
            expectError(google, 403, "INSTALLATION_ALREADY_REGISTERED");
            assertThat((String) read(google, "$.error.reason")).isEqualTo("KAKAO");
        }

        @Test
        @DisplayName("구글로 탈퇴했다가 구글로 돌아오는 건 통과한다 — 막히는 건 다른 소셜뿐")
        void google_owner_can_come_back() throws Exception {
            String tag = uniq("g");
            String install = "inst-" + tag;
            MvcResult first = googleSignup(tag, "구글복귀" + seq(), install);
            String firstUserId = read(first, "$.data.user.id");
            withdraw(read(first, "$.data.accessToken"));

            MvcResult login = postJson("/api/v1/auth/oauth/google",
                    googleLoginBody(tag, install, "dev-" + tag));
            assertThat(login.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(login, "$.data.isNewUser")).isTrue();
            assertThat((Boolean) read(login, "$.data.returningUser")).isTrue();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("signupToken", read(login, "$.data.signupToken"));
            body.put("installationId", install);
            body.put("deviceId", "dev-" + tag);
            body.put("deviceInfo", deviceInfo());
            MvcResult res = postJson("/api/v1/auth/signup", body);   // 입력 없이 복원

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.restored")).isTrue();
            assertThat((String) read(res, "$.data.user.id")).isEqualTo(firstUserId);
        }
    }

    // ==================================================================
    // 1) 구글 경로
    // ==================================================================

    @Nested
    @DisplayName("구글 로그인")
    class GoogleLogin {

        @Test
        @DisplayName("구글 신규 로그인 → signupToken + 프리필, 가입 후 재로그인은 기존 회원으로 잡힌다")
        void google_signup_and_relogin() throws Exception {
            String tag = uniq("gg");

            MvcResult newUser = postJson("/api/v1/auth/oauth/google",
                    googleLoginBody(tag, "inst-" + tag, "dev-" + tag));
            assertThat(newUser.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(newUser, "$.data.isNewUser")).isTrue();
            assertThat((String) read(newUser, "$.data.oauthProfile.nicknameHint")).isNotBlank();
            assertThat((Object) read(newUser, "$.data.oauthProfile.birthdayHint")).isNull();

            String signupToken = read(newUser, "$.data.signupToken");
            Map<String, Object> body = signupBody(signupToken, "구글유저" + seq(), "inst-" + tag, "dev-" + tag);
            MvcResult signup = postJson("/api/v1/auth/signup", body);
            assertThat(signup.getResponse().getStatus()).isEqualTo(200);
            String userId = read(signup, "$.data.user.id");

            // 저장된 provider 는 GOOGLE
            assertThat(userRepository.findByOauthProviderAndOauthSubject(
                    OAuthProvider.GOOGLE, "mock-google-" + tag)).isPresent();

            MvcResult relogin = postJson("/api/v1/auth/oauth/google",
                    googleLoginBody(tag, "inst-" + tag, "dev-" + tag));
            assertThat((Boolean) read(relogin, "$.data.isNewUser")).isFalse();
            assertThat((String) read(relogin, "$.data.user.id")).isEqualTo(userId);
        }

        @Test
        @DisplayName("같은 식별자라도 provider 가 다르면 별개 계정이다 (카카오 ≠ 구글)")
        void same_subject_different_provider_is_separate_account() throws Exception {
            String tag = uniq("mix");
            MvcResult kakao = signup(tag, "카카오쪽" + seq());
            String kakaoUserId = read(kakao, "$.data.user.id");

            // 같은 code(=subject 원본)로 구글 로그인 → 신규 가입 분기여야 한다
            MvcResult google = postJson("/api/v1/auth/oauth/google",
                    googleLoginBody(tag, "inst-" + tag + "-g", "dev-" + tag + "-g"));
            assertThat(google.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(google, "$.data.isNewUser")).isTrue();

            Map<String, Object> body = signupBody(read(google, "$.data.signupToken"),
                    "구글쪽" + seq(), "inst-" + tag + "-g", "dev-" + tag + "-g");
            MvcResult signup = postJson("/api/v1/auth/signup", body);
            assertThat((String) read(signup, "$.data.user.id")).isNotEqualTo(kakaoUserId);
        }
    }

    // ==================================================================
    // 2) 요청 형식 검증
    // ==================================================================

    @Nested
    @DisplayName("요청 형식")
    class RequestFormat {

        @Test
        @DisplayName("code 누락은 400 LOGIN_FAILED — 검증 자체가 불가")
        void missing_code_rejected() throws Exception {
            String tag = uniq("nc");
            Map<String, Object> body = loginBody(tag, "inst-" + tag, "dev-" + tag);
            body.remove("code");
            expectError(postJson("/api/v1/auth/oauth/kakao", body), 400, "LOGIN_FAILED");
        }

        @Test
        @DisplayName("codeVerifier(PKCE) 누락은 400 LOGIN_FAILED")
        void missing_code_verifier_rejected() throws Exception {
            String tag = uniq("nv");
            Map<String, Object> body = loginBody(tag, "inst-" + tag, "dev-" + tag);
            body.remove("codeVerifier");
            expectError(postJson("/api/v1/auth/oauth/kakao", body), 400, "LOGIN_FAILED");
        }

        @Test
        @DisplayName("구글 redirectUri 불일치는 400 INVALID_REDIRECT_URI")
        void google_redirect_uri_mismatch_rejected() throws Exception {
            String tag = uniq("gr");
            Map<String, Object> body = googleLoginBody(tag, "inst-" + tag, "dev-" + tag);
            body.put("redirectUri", "https://attacker.example/callback");
            expectError(postJson("/api/v1/auth/oauth/google", body), 400, "INVALID_REDIRECT_URI");
        }

        @Test
        @DisplayName("구글 redirectUri 누락도 400 INVALID_REDIRECT_URI (구글은 검증 필수)")
        void google_redirect_uri_missing_rejected() throws Exception {
            String tag = uniq("gn");
            Map<String, Object> body = googleLoginBody(tag, "inst-" + tag, "dev-" + tag);
            body.remove("redirectUri");
            expectError(postJson("/api/v1/auth/oauth/google", body), 400, "INVALID_REDIRECT_URI");
        }

        @Test
        @DisplayName("카카오는 redirectUri 가 null 이어도 로그인된다 (카카오톡 간편 로그인 경로)")
        void kakao_null_redirect_uri_allowed() throws Exception {
            String tag = uniq("kn");
            Map<String, Object> body = loginBody(tag, "inst-" + tag, "dev-" + tag);
            body.remove("redirectUri");
            MvcResult res = postJson("/api/v1/auth/oauth/kakao", body);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.isNewUser")).isTrue();
        }

        @Test
        @DisplayName("알 수 없는 provider 는 400 LOGIN_FAILED")
        void unknown_provider_rejected() throws Exception {
            String tag = uniq("up");
            expectError(postJson("/api/v1/auth/oauth/facebook",
                    loginBody(tag, "inst-" + tag, "dev-" + tag)), 400, "LOGIN_FAILED");
        }
    }

    // ==================================================================
    // 3) IdP 실패
    // ==================================================================

    @Nested
    @DisplayName("IdP 실패")
    class IdpFailure {

        @Test
        @DisplayName("인가 코드 검증 실패는 400 LOGIN_FAILED")
        void invalid_authorization_code_rejected() throws Exception {
            String tag = uniq(MockOAuthClient.FAIL_INVALID_CODE + "-");
            expectError(postJson("/api/v1/auth/oauth/kakao",
                    loginBody(tag, "inst-" + tag, "dev-" + tag)), 400, "LOGIN_FAILED");
        }

        @Test
        @DisplayName("IdP 장애(타임아웃·5xx)는 502 LOGIN_PROVIDER_UNAVAILABLE — 가드레일 지표 대상")
        void idp_outage_returns_502() throws Exception {
            String tag = uniq(MockOAuthClient.FAIL_IDP_DOWN + "-");
            expectError(postJson("/api/v1/auth/oauth/kakao",
                    loginBody(tag, "inst-" + tag, "dev-" + tag)), 502, "LOGIN_PROVIDER_UNAVAILABLE");
        }

        @Test
        @DisplayName("IdP 실패 시 계정은 만들어지지 않는다")
        void failed_login_creates_no_account() throws Exception {
            long before = userRepository.count();
            String tag = uniq(MockOAuthClient.FAIL_INVALID_CODE + "-");
            postJson("/api/v1/auth/oauth/kakao", loginBody(tag, "inst-" + tag, "dev-" + tag));
            postJson("/api/v1/auth/oauth/google", googleLoginBody(
                    uniq(MockOAuthClient.FAIL_IDP_DOWN + "-"), "inst-x" + tag, "dev-x" + tag));
            assertThat(userRepository.count()).isEqualTo(before);
        }
    }

    // ==================================================================
    // 4) 기기 정보 (로그인 단계)
    // ==================================================================

    @Nested
    @DisplayName("기기 정보")
    class DeviceInfo {

        @Test
        @DisplayName("로그인도 deviceId·deviceInfo 가 필수다 — 400 INVALID_DEVICE_INFO")
        void login_requires_device() throws Exception {
            String tag = uniq("ld");
            Map<String, Object> noDeviceId = loginBody(tag, "inst-" + tag, "dev-" + tag);
            noDeviceId.remove("deviceId");
            expectError(postJson("/api/v1/auth/oauth/kakao", noDeviceId), 400, "INVALID_DEVICE_INFO");

            Map<String, Object> noDeviceInfo = loginBody(tag, "inst-" + tag, "dev-" + tag);
            noDeviceInfo.remove("deviceInfo");
            expectError(postJson("/api/v1/auth/oauth/kakao", noDeviceInfo), 400, "INVALID_DEVICE_INFO");
        }

        @Test
        @DisplayName("permissions 스냅샷을 보내도 로그인에 영향이 없다 (서버 저장 안 함 — 참고용)")
        void permissions_snapshot_is_optional() throws Exception {
            String tag = uniq("pm");
            signup(tag, "권한유저" + seq());

            Map<String, Object> body = loginBody(tag, "inst-" + tag, "dev-" + tag);
            Map<String, Object> permissions = new LinkedHashMap<>();
            permissions.put("location", "GRANTED");
            permissions.put("postNotifications", "DENIED");
            body.put("permissions", permissions);

            MvcResult res = postJson("/api/v1/auth/oauth/kakao", body);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.isNewUser")).isFalse();
            // 응답의 device 는 저장된 기기 스펙 에코 — permissions 는 어디에도 반영되지 않는다
            assertThat((String) read(res, "$.data.device.platform")).isEqualTo("ANDROID");
        }
    }
}
