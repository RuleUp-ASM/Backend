package com.ruleup.ruleup_backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.NicknameStatus;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 탈퇴·복원 스펙 테스트 (회원 정책 §6 · DB 정리 §12 · 회원 탈퇴/내 프로필 API 계약).
 *
 * 커버 범위
 *  1) 탈퇴: confirmPhrase 검증(불일치 400), 소프트 탈퇴(WITHDRAWN+deleted_at),
 *     기기 연결 해제, RT 전부 revoke, 탈퇴 후 보호 API 차단, 멱등
 *  2) 자원 해제: 탈퇴자 닉네임·설치(installationId)를 타인이 재사용 가능
 *  3) 복원: 1년 내 동일 소셜 계정 재로그인 → restored=true·같은 user id·데이터 유지
 *  4) 복원 닉네임 충돌: 타인이 선점했으면 nicknameStatus=CONFLICT + 임시 승인 닉네임
 *  5) 차단 대상: BANNED 는 탈퇴 불가(제재 세탁 방지) — 로그인·재가입도 계속 403
 *  6) GET /users/me: user 블록 + 생일·성별·약관 6종 {agreed, version, agreedAt}
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WithdrawRestoreFlowIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired WebApplicationContext wac;
    private final ObjectMapper om = new ObjectMapper();
    @Autowired UserRepository userRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    // ==================================================================
    // 헬퍼
    // ==================================================================

    private static String uniq() {
        return "wr" + System.nanoTime() + "n" + SEQ.incrementAndGet();
    }

    private Map<String, Object> deviceInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform", "ANDROID");
        m.put("osVersion", "14");
        m.put("sdkInt", 34);
        m.put("deviceModel", "SM-S921N");
        m.put("manufacturer", "samsung");
        m.put("lowRam", false);
        m.put("versionName", "1.0.0");
        m.put("versionCode", 100);
        return m;
    }

    private Map<String, Object> loginBody(String code, String installationId, String deviceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("codeVerifier", "verifier");
        m.put("redirectUri", "kakao://oauth");
        m.put("installationId", installationId);
        m.put("deviceId", deviceId);
        m.put("deviceInfo", deviceInfo());
        return m;
    }

    private Map<String, Object> agreement(boolean agreed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agreed", agreed);
        m.put("version", "1.0");
        return m;
    }

    private MvcResult postJson(String url, Map<String, Object> body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body))).andReturn();
    }

    @SuppressWarnings("unchecked")
    private <T> T read(MvcResult res, String path) throws Exception {
        return (T) JsonPath.read(res.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    /** 가입 완료 응답을 돌려준다 (accessToken/refreshToken 추출용). */
    private MvcResult signup(String tag, String nickname) throws Exception {
        MvcResult login = postJson("/api/v1/auth/oauth/kakao", loginBody(tag, "inst-" + tag, "dev-" + tag));
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        String token = read(login, "$.data.signupToken");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signupToken", token);
        body.put("installationId", "inst-" + tag);
        body.put("nickname", nickname);
        body.put("interestCategories", List.of("EXERCISE", "READING"));
        body.put("birthDate", "2000-05-27");
        body.put("gender", "MALE");
        Map<String, Object> ag = new LinkedHashMap<>();
        ag.put("termsOfService", agreement(true));
        ag.put("privacyPolicy", agreement(true));
        ag.put("locationService", agreement(true));
        ag.put("marketing", agreement(true));
        ag.put("event", agreement(false));
        ag.put("nightPush", agreement(false));
        body.put("agreements", ag);
        body.put("deviceId", "dev-" + tag);
        body.put("deviceInfo", deviceInfo());

        MvcResult res = postJson("/api/v1/auth/signup", body);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res;
    }

    private User findUser(String tag) {
        return userRepository.findByOauthProviderAndOauthSubject(OAuthProvider.KAKAO, "mock-kakao-" + tag)
                .orElseThrow();
    }

    private MvcResult withdraw(String accessToken, String confirmPhrase) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirmPhrase", confirmPhrase);
        return mvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(m))).andReturn();
    }

    private MvcResult me(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)).andReturn();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refreshToken", refreshToken);
        return postJson("/api/v1/auth/refresh", m);
    }

    private void expectError(MvcResult res, int status, String code) throws Exception {
        assertThat(res.getResponse().getStatus()).as("HTTP status").isEqualTo(status);
        assertThat((String) read(res, "$.error.code")).isEqualTo(code);
    }

    // ==================================================================
    // 1) 탈퇴
    // ==================================================================

    @Nested
    @DisplayName("탈퇴")
    class Withdrawal {

        @Test
        @DisplayName("확인 문구와 함께 탈퇴하면 소프트 탈퇴되고 세션이 모두 끊긴다")
        void withdraw_soft_deletes_and_kills_sessions() throws Exception {
            String tag = uniq();
            MvcResult signup = signup(tag, "탈퇴예정" + SEQ.get());
            String at = read(signup, "$.data.accessToken");
            String rt = read(signup, "$.data.refreshToken");

            MvcResult res = withdraw(at, "탈퇴할게요");
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.withdrawn")).isTrue();
            assertThat((String) read(res, "$.data.archiveExpiresAt")).isNotBlank();   // 탈퇴 +1년
            assertThat((String) read(res, "$.data.restoreNote")).isNotBlank();

            User user = findUser(tag);
            assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
            assertThat(user.getDeletedAt()).isNotNull();
            assertThat(user.getInstallationId()).isNull();   // 설치 연결 해제(다른 계정 가입 허용)
            assertThat(user.getDeviceId()).isNull();

            expectError(refresh(rt), 401, "SESSION_EXPIRED");   // RT 전부 revoke
            expectError(me(at), 401, "LOGIN_REQUIRED");          // 탈퇴 후 보호 API 차단
        }

        @Test
        @DisplayName("확인 문구가 다르면 400 CONFIRM_PHRASE_MISMATCH — 탈퇴되지 않는다")
        void wrong_confirm_phrase_rejected() throws Exception {
            String tag = uniq();
            String at = read(signup(tag, "문구오류" + SEQ.get()), "$.data.accessToken");

            expectError(withdraw(at, "탈퇴 할게요"), 400, "CONFIRM_PHRASE_MISMATCH");
            assertThat(findUser(tag).getStatus()).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("탈퇴는 멱등 — 이미 탈퇴한 계정의 재요청도 무해하게 200")
        void withdraw_is_idempotent() throws Exception {
            String tag = uniq();
            String at = read(signup(tag, "멱등탈퇴" + SEQ.get()), "$.data.accessToken");

            assertThat(withdraw(at, "탈퇴할게요").getResponse().getStatus()).isEqualTo(200);
            MvcResult second = withdraw(at, "탈퇴할게요");
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(second, "$.data.withdrawn")).isTrue();
        }

        @Test
        @DisplayName("영구 정지(BANNED) 계정은 탈퇴로 제재를 세탁할 수 없다 — 403")
        void banned_cannot_withdraw() throws Exception {
            String tag = uniq();
            String at = read(signup(tag, "정지유저" + SEQ.get()), "$.data.accessToken");
            User user = findUser(tag);
            user.ban();
            userRepository.save(user);

            expectError(withdraw(at, "탈퇴할게요"), 403, "ACCOUNT_BANNED");
            assertThat(findUser(tag).getStatus()).isEqualTo(UserStatus.BANNED);
        }
    }

    // ==================================================================
    // 2) 탈퇴 후 자원 해제
    // ==================================================================

    @Nested
    @DisplayName("자원 해제")
    class ResourceRelease {

        @Test
        @DisplayName("탈퇴자의 닉네임은 해제되어 신규 가입자가 사용할 수 있다")
        void withdrawn_nickname_released() throws Exception {
            String tag = uniq();
            String nickname = "해제닉" + tag.substring(2, 9);
            String at = read(signup(tag, nickname), "$.data.accessToken");
            assertThat(withdraw(at, "탈퇴할게요").getResponse().getStatus()).isEqualTo(200);

            // 같은 닉네임으로 신규 가입 성공
            MvcResult res = signup(uniq(), nickname);
            assertThat((String) read(res, "$.data.user.nickname")).isEqualTo(nickname);
        }

        @Test
        @DisplayName("탈퇴하면 설치(installationId)가 해제되어 같은 기기에서 다른 계정 가입이 가능하다")
        void withdrawn_installation_released() throws Exception {
            String tag = uniq();
            String at = read(signup(tag, "설치해제" + SEQ.get()), "$.data.accessToken");
            assertThat(withdraw(at, "탈퇴할게요").getResponse().getStatus()).isEqualTo(200);

            // 같은 설치에서 새 소셜 계정 로그인 → 신규 가입 분기 허용 (403 아님)
            String tag2 = uniq();
            MvcResult login = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(tag2, "inst-" + tag, "dev-" + tag));
            assertThat(login.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(login, "$.data.isNewUser")).isTrue();
        }
    }

    // ==================================================================
    // 3) 복원
    // ==================================================================

    @Nested
    @DisplayName("복원")
    class Restoration {

        @Test
        @DisplayName("탈퇴 1년 내 동일 소셜 계정 재로그인은 신규 가입이 아니라 복원이다 — restored=true·데이터 유지")
        void relogin_restores_account() throws Exception {
            String tag = uniq();
            MvcResult signup = signup(tag, "복원유저" + SEQ.get());
            String firstUserId = read(signup, "$.data.user.id");
            String at = read(signup, "$.data.accessToken");
            assertThat(withdraw(at, "탈퇴할게요").getResponse().getStatus()).isEqualTo(200);

            MvcResult relogin = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(tag, "inst-" + tag + "-new", "dev-" + tag + "-new"));
            assertThat(relogin.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(relogin, "$.data.isNewUser")).isFalse();   // 신규 가입 아님
            assertThat((Boolean) read(relogin, "$.data.restored")).isTrue();
            assertThat((String) read(relogin, "$.data.user.id")).isEqualTo(firstUserId);   // 같은 계정
            List<String> interests = read(relogin, "$.data.user.interestCategories");
            assertThat(interests).containsExactlyInAnyOrder("EXERCISE", "READING");   // 데이터 유지

            User user = findUser(tag);
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getDeletedAt()).isNull();
            assertThat(user.getInstallationId()).isEqualTo("inst-" + tag + "-new");
        }

        @Test
        @DisplayName("복원 시 닉네임을 타인이 선점했으면 CONFLICT + 임시 닉네임으로 재설정을 유도한다")
        void restore_with_taken_nickname_conflicts() throws Exception {
            String tag = uniq();
            String nickname = "선점될닉" + tag.substring(2, 8);
            MvcResult signup = signup(tag, nickname);
            String at = read(signup, "$.data.accessToken");

            // 승인된 닉네임으로 만들어 두고 탈퇴
            User original = findUser(tag);
            original.approveNickname();
            userRepository.save(original);
            String approvedBefore = original.getApprovedNickname();
            assertThat(withdraw(at, "탈퇴할게요").getResponse().getStatus()).isEqualTo(200);

            // 타인이 같은 닉네임 선점
            signup(uniq(), nickname);

            // 원래 주인이 재로그인 → 복원되지만 닉네임은 CONFLICT
            MvcResult relogin = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(tag, "inst-" + tag + "-r", "dev-" + tag + "-r"));
            assertThat(relogin.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(relogin, "$.data.restored")).isTrue();
            assertThat((String) read(relogin, "$.data.user.nicknameStatus")).isEqualTo("CONFLICT");

            User restored = findUser(tag);
            assertThat(restored.getNicknameStatus()).isEqualTo(NicknameStatus.CONFLICT);
            assertThat(restored.getApprovedNickname()).isNotEqualTo(approvedBefore);   // 임시 8자로 교체
            assertThat(restored.getApprovedNickname()).hasSize(8);
            assertThat(restored.getNickname()).isEqualTo(nickname);   // 표시용으로 기존 닉네임 유지
        }
    }

    // ==================================================================
    // 4) 내 프로필 조회
    // ==================================================================

    @Nested
    @DisplayName("내 프로필")
    class MyProfile {

        @Test
        @DisplayName("GET /users/me — user 블록 + 생일·성별·약관 6종 상태를 내려준다")
        void me_returns_full_profile() throws Exception {
            String tag = uniq();
            String at = read(signup(tag, "프로필조회" + SEQ.get()), "$.data.accessToken");

            MvcResult res = me(at);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.user.nickname")).startsWith("프로필조회");
            assertThat((String) read(res, "$.data.user.tier")).isEqualTo("BRONZE");
            assertThat((Integer) read(res, "$.data.user.score")).isEqualTo(10);
            assertThat((String) read(res, "$.data.user.accountStatus")).isEqualTo("ACTIVE");
            assertThat((String) read(res, "$.data.birthDate")).isEqualTo("2000-05-27");
            assertThat((String) read(res, "$.data.gender")).isEqualTo("MALE");
            assertThat((Boolean) read(res, "$.data.agreements.termsOfService.agreed")).isTrue();
            assertThat((String) read(res, "$.data.agreements.termsOfService.version")).isEqualTo("1.0");
            assertThat((String) read(res, "$.data.agreements.termsOfService.agreedAt")).isNotBlank();
            assertThat((Boolean) read(res, "$.data.agreements.marketing.agreed")).isTrue();
            assertThat((Boolean) read(res, "$.data.agreements.event.agreed")).isFalse();
            assertThat((Boolean) read(res, "$.data.agreements.nightPush.agreed")).isFalse();
        }

        @Test
        @DisplayName("토큰 없이 GET /users/me 는 401 LOGIN_REQUIRED")
        void me_requires_login() throws Exception {
            MvcResult res = mvc.perform(get("/api/v1/users/me")).andReturn();
            expectError(res, 401, "LOGIN_REQUIRED");
        }
    }
}
