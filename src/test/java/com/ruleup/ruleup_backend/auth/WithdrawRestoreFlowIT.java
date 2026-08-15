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
 *  2) 자원 해제: 탈퇴자 닉네임은 타인이 재사용 가능. 단 설치(installationId)는 계속 묶여 있다
 *  3) 설치 게이트: 같은 기기에서 다른 소셜로 신규 가입은 차단(세탁 방지) — 원래 소셜로는 복귀 가능
 *  4) 복원: 동일 소셜 계정 재로그인 → restored=true·같은 user id·데이터 유지
 *  5) 복원 닉네임 충돌: 타인이 선점했으면 nicknameStatus=CONFLICT + 임시 승인 닉네임
 *  6) 정지 계정: 탈퇴는 허용하되 제재가 따라온다(복원 시 정지 그대로, 재로그인 403)
 *  7) GET /users/me: user 블록 + 생일·성별·약관 6종 {agreed, version, agreedAt}
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
        return signupOn(tag, nickname, "inst-" + tag);
    }

    /** 설치 ID 를 지정해 가입한다 — 같은 기기에서 다른 소셜 계정으로 재가입하는 승계 시나리오용. */
    private MvcResult signupOn(String tag, String nickname, String installationId) throws Exception {
        MvcResult login = postJson("/api/v1/auth/oauth/kakao", loginBody(tag, installationId, "dev-" + tag));
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        String token = read(login, "$.data.signupToken");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signupToken", token);
        body.put("installationId", installationId);
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

    /**
     * 탈퇴자의 복귀 플로우 — 소셜 로그인으로 signupToken 을 받고, <b>온보딩 입력 없이</b> 가입을 요청한다.
     * 이전 정보가 있으면 서버가 그대로 살려 로그인시킨다.
     */
    private MvcResult comeBack(String tag, String installationId) throws Exception {
        MvcResult login = postJson("/api/v1/auth/oauth/kakao",
                loginBody(tag, installationId, "dev-" + tag));
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) read(login, "$.data.isNewUser")).isTrue();          // 복귀도 가입 분기를 탄다
        assertThat((Boolean) read(login, "$.data.returningUser")).isTrue();      // 클라는 입력 화면을 띄우지 않는다

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signupToken", read(login, "$.data.signupToken"));
        body.put("installationId", installationId);
        body.put("deviceId", "dev-" + tag);
        body.put("deviceInfo", deviceInfo());
        return postJson("/api/v1/auth/signup", body);   // 닉네임·생일·약관 없음
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
            // 설치 ID 는 남긴다 — 같은 기기에서 재가입할 때 상태·점수를 승계하는 근거다.
            // 같은 기기의 새 가입은 UNIQUE 생성 컬럼(active_installation_id)이 탈퇴 행을 빼주므로 그대로 된다.
            assertThat(user.getInstallationId()).isNotNull();
            assertThat(user.getDeviceId()).isNull();         // 기기 연결은 해제(단일 활성 기기 판정용 현재 상태)

            expectError(refresh(rt), 401, "SESSION_EXPIRED");   // RT 전부 revoke
            expectError(me(at), 401, "LOGIN_REQUIRED");          // 탈퇴 후 보호 API 차단
        }

        // 회원 탈퇴 UI가 나오면 확정
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
        @DisplayName("영구 정지(BANNED) 계정도 탈퇴할 수 있다 — 막는 대신 제재를 따라오게 한다")
        void banned_can_withdraw_but_sanction_is_kept() throws Exception {
            String tag = uniq();
            String at = read(signup(tag, "정지유저" + SEQ.get()), "$.data.accessToken");
            User user = findUser(tag);
            user.ban();
            userRepository.save(user);

            assertThat(withdraw(at, "탈퇴할게요").getResponse().getStatus()).isEqualTo(200);

            User withdrawn = findUser(tag);
            assertThat(withdrawn.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
            // 정지였다는 사실이 남아야 재가입 승계가 가능하다 — 이게 없으면 탈퇴가 곧 세탁이다.
            assertThat(withdrawn.carriedOverStatus()).isEqualTo(UserStatus.BANNED);
            assertThat(withdrawn.getInstallationId()).isNotNull();
        }

        @Test
        @DisplayName("정지 상태로 탈퇴해도 회원가입까지는 되고 로그인만 막힌다 — 403 + 계정은 정지로 복원")
        void banned_can_sign_up_again_but_cannot_log_in() throws Exception {
            String tag = uniq();
            String at = read(signup(tag, "정지복원" + SEQ.get()), "$.data.accessToken");
            User user = findUser(tag);
            user.ban();
            userRepository.save(user);
            withdraw(at, "탈퇴할게요");

            expectError(comeBack(tag, "inst-" + tag), 403, "ACCOUNT_BANNED");   // 토큰을 주지 않는다

            // 가입(복원) 자체는 커밋됐다 — 롤백해버리면 "가입까지는 된다"가 성립하지 않는다
            User restored = findUser(tag);
            assertThat(restored.getStatus()).isEqualTo(UserStatus.BANNED);
            assertThat(restored.getDeletedAt()).isNull();
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
        @DisplayName("탈퇴해도 설치(installationId)는 묶여 있어 다른 소셜 계정의 신규 가입은 막힌다")
        void withdrawn_installation_stays_claimed() throws Exception {
            String tag = uniq();
            String at = read(signup(tag, "설치유지" + SEQ.get()), "$.data.accessToken");
            assertThat(withdraw(at, "탈퇴할게요").getResponse().getStatus()).isEqualTo(200);

            // 같은 설치 + 다른 소셜 계정 → 온보딩을 채우기 전에, 로그인 단계에서 끊는다.
            // 열어주면 소셜만 바꿔 점수·제재를 리셋할 수 있다(세탁).
            String tag2 = uniq();
            MvcResult login = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(tag2, "inst-" + tag, "dev-" + tag));
            expectError(login, 403, "INSTALLATION_ALREADY_REGISTERED");
            // 어느 계정으로 가야 하는지 알려준다 — 없으면 사용자는 무엇을 해야 할지 모른다
            assertThat((String) read(login, "$.error.reason")).isEqualTo("KAKAO");
        }

        @Test
        @DisplayName("막히는 건 신규 가입뿐 — 원래 소셜 계정으로는 같은 기기에서 돌아올 수 있다")
        void same_social_account_can_still_come_back() throws Exception {
            String tag = uniq();
            String at = read(signup(tag, "복귀가능" + SEQ.get()), "$.data.accessToken");
            java.util.UUID before = findUser(tag).getId();
            withdraw(at, "탈퇴할게요");

            MvcResult res = comeBack(tag, "inst-" + tag);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.restored")).isTrue();
            assertThat(findUser(tag).getId()).isEqualTo(before);   // 같은 계정 그대로
        }
    }

    // ==================================================================
    // 2-1) 설치 게이트 — 같은 기기에서 소셜만 바꿔 새로 시작하는 경로를 막는다
    // ==================================================================

    @Nested
    @DisplayName("설치 게이트")
    class InstallationGate {

        @Test
        @DisplayName("가입 API 를 직접 찔러도 막힌다 — 로그인 단계와 가입 단계 양쪽에 게이트가 있다")
        void signup_endpoint_is_also_gated() throws Exception {
            String tag = uniq();
            String at = read(signup(tag, "직접호출" + SEQ.get()), "$.data.accessToken");
            withdraw(at, "탈퇴할게요");

            // 로그인 게이트를 우회하려고 다른 설치로 signupToken 만 받아온 뒤,
            // 가입 요청에서 탈퇴자가 쓰던 설치 ID 를 끼워 넣는 시나리오.
            String tag2 = uniq();
            MvcResult login = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(tag2, "inst-clean" + SEQ.incrementAndGet(), "dev-" + tag2));
            String token = read(login, "$.data.signupToken");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("signupToken", token);
            body.put("installationId", "inst-" + tag);          // ← 탈퇴자가 쓰던 설치
            body.put("nickname", "우회가입" + SEQ.get());
            body.put("interestCategories", List.of("EXERCISE"));
            body.put("birthDate", "2000-05-27");
            body.put("gender", "MALE");
            Map<String, Object> ag = new LinkedHashMap<>();
            ag.put("termsOfService", agreement(true));
            ag.put("privacyPolicy", agreement(true));
            ag.put("locationService", agreement(true));
            body.put("agreements", ag);
            body.put("deviceId", "dev-" + tag2);
            body.put("deviceInfo", deviceInfo());

            expectError(postJson("/api/v1/auth/signup", body), 403, "INSTALLATION_ALREADY_REGISTERED");
        }

        @Test
        @DisplayName("막히는 건 신규 가입뿐 — 기존 회원은 탈퇴자가 쓰던 기기에서도 로그인된다")
        void existing_member_can_log_in_on_a_withdrawn_installation() throws Exception {
            String gone = uniq();
            String at = read(signup(gone, "떠난사람" + SEQ.get()), "$.data.accessToken");
            withdraw(at, "탈퇴할게요");

            // 다른 기기에서 이미 가입해 둔 기존 회원이, 탈퇴자가 쓰던 기기로 옮겨온 상황
            String mover = uniq();
            signupOn(mover, "이사온사람" + SEQ.get(), "inst-own" + SEQ.incrementAndGet());
            MvcResult login = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(mover, "inst-" + gone, "dev-" + mover));

            assertThat(login.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(login, "$.data.isNewUser")).isFalse();
            // 탈퇴 행과 활성 행이 같은 설치 ID 를 동시에 들고 있어도 UNIQUE 가 터지지 않는다
            // (active_installation_id 생성 컬럼이 탈퇴 행을 대상에서 뺀다)
            assertThat(findUser(mover).getInstallationId()).isEqualTo("inst-" + gone);
            assertThat(findUser(gone).getInstallationId()).isEqualTo("inst-" + gone);
        }

        @Test
        @DisplayName("앱을 지웠다 깔면 설치 ID 가 바뀌어 막지 못한다 — 수용된 한계")
        void reinstall_bypasses_the_gate() throws Exception {
            String tag = uniq();
            String at = read(signup(tag, "재설치전" + SEQ.get()), "$.data.accessToken");
            withdraw(at, "탈퇴할게요");

            // 재설치 = 새 installationId. 작정한 세탁까지 막는 게 목적이 아니라
            // 무심코 소셜만 바꿔 다시 시작하는 경로를 막는 장치다.
            String tag2 = uniq();
            MvcResult res = signupOn(tag2, "재설치후" + SEQ.get(), "inst-new" + SEQ.incrementAndGet());
            assertThat((Integer) read(res, "$.data.user.score")).isEqualTo(10);
        }
    }

    // ==================================================================
    // 3) 복원
    // ==================================================================

    @Nested
    @DisplayName("복원")
    class Restoration {

        @Test
        @DisplayName("탈퇴 후 복귀는 가입 요청에서 복원된다 — 입력 없이 restored=true·데이터 유지")
        void signup_restores_withdrawn_account() throws Exception {
            String tag = uniq();
            MvcResult signup = signup(tag, "복원유저" + SEQ.get());
            String firstUserId = read(signup, "$.data.user.id");
            String at = read(signup, "$.data.accessToken");
            assertThat(withdraw(at, "탈퇴할게요").getResponse().getStatus()).isEqualTo(200);

            MvcResult res = comeBack(tag, "inst-" + tag);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.isNewUser")).isFalse();   // 새 계정이 아니다
            assertThat((Boolean) read(res, "$.data.restored")).isTrue();
            assertThat((String) read(res, "$.data.user.id")).isEqualTo(firstUserId);   // 같은 계정
            assertThat((String) read(res, "$.data.accessToken")).isNotBlank();          // 그대로 로그인된다
            List<String> interests = read(res, "$.data.user.interestCategories");
            assertThat(interests).containsExactlyInAnyOrder("EXERCISE", "READING");   // 입력 없이 이전 정보 유지

            User user = findUser(tag);
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getDeletedAt()).isNull();   // 복원되면 다시 비워진다
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

            // 원래 주인이 돌아오면 — 막지 않고 임시 닉네임으로 들여보낸 뒤 변경을 요청한다
            MvcResult res = comeBack(tag, "inst-" + tag);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.restored")).isTrue();
            assertThat((String) read(res, "$.data.user.nicknameStatus")).isEqualTo("CONFLICT");

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
