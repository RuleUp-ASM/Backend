package com.ruleup.ruleup_backend.e2e;

import com.jayway.jsonpath.JsonPath;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * end-to-end happy-path(실 MySQL + 실 보안필터/JWT + 컨트롤러 HTTP):
 *   회원가입 → 로그인 → 챌린지 생성 → 조회(게이트) → 자동인증(수동 SUCCESS + 폴백 방장승인).
 *
 * <p>OAuth는 test 프로파일의 MockOAuthClient(같은 code → 같은 사용자)로 대체된다.
 * 외부의존(Gemini/SMS)은 fake/null-fallback이라 실제 키 없이 전 구간이 돈다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthChallengeVerificationE2EIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired WebApplicationContext wac;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 실제 보안필터(JWT) 체인을 적용한 MockMvc — 컨트롤러+필터+JSON 바인딩 전 구간 검증.
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private static final String DEVICE_INFO = """
            {"platform":"ANDROID","osVersion":"14","sdkInt":34,"deviceModel":"SM-S921N",
             "manufacturer":"samsung","lowRam":false,"versionName":"1.0.0","versionCode":100}""";

    @Test
    @DisplayName("가입→로그인→생성→조회→인증(수동+폴백승인) 전 구간이 HTTP로 돈다")
    void full_happy_path() throws Exception {
        // ── 1) 소셜 로그인(신규) → signupToken ──────────────────────────────
        MvcResult oauthNew = postJson("/api/v1/auth/oauth/kakao", """
                {"code":"e2e-owner","codeVerifier":"v","redirectUri":"ruleup://login/callback","deviceInfo":%s}"""
                .formatted(DEVICE_INFO));
        assertThat((Boolean) read(oauthNew, "$.data.isNewUser")).isTrue();
        String signupToken = read(oauthNew, "$.data.signupToken");

        // ── 2) 가입 완료 → accessToken (+ nicknameStatus=PENDING 계약 확인) ──
        MvcResult signup = postJson("/api/v1/auth/signup", """
                {"signupToken":"%s","nickname":"도전왕E2E","interestCategories":["EXERCISE"],
                 "agreements":{"termsOfService":{"agreed":true,"version":"1.0"},
                               "privacyPolicy":{"agreed":true,"version":"1.0"},
                               "marketing":{"agreed":false,"version":"1.0"}},
                 "deviceInfo":%s}""".formatted(signupToken, DEVICE_INFO));
        assertThat((Boolean) read(signup, "$.data.isNewUser")).isTrue();
        assertThat((String) read(signup, "$.data.user.nicknameStatus")).isEqualTo("PENDING");

        // ── 3) 로그인(기존, 같은 code) → isNewUser=false + accessToken ───────
        MvcResult login = postJson("/api/v1/auth/oauth/kakao", """
                {"code":"e2e-owner","codeVerifier":"v","redirectUri":"ruleup://login/callback","deviceInfo":%s}"""
                .formatted(DEVICE_INFO));
        assertThat((Boolean) read(login, "$.data.isNewUser")).isFalse();
        String ownerToken = read(login, "$.data.accessToken");

        // ── 4) 챌린지 생성(MANUAL) → RECRUITING / PENDING_REVIEW ────────────
        String today = LocalDate.now(KST).toString();
        MvcResult created = postJsonAuth("/api/v1/challenges", ownerToken, """
                {"title":"아침 7시 기상","description":null,"imageUrl":null,"category":"EXERCISE",
                 "participationType":"SOLO","minMannerTemperature":null,
                 "repeatDays":["MON","TUE","WED","THU","FRI"],"durationDays":14,"startDate":"%s",
                 "templateId":null,"selectedMethod":"MANUAL","params":{},
                 "penalty":{"mannerDeduction":1},"reward":{"mannerGain":1},"anonymity":"REAL"}"""
                .formatted(today));
        String challengeId = read(created, "$.data.challengeId");
        assertThat((String) read(created, "$.data.status")).isEqualTo("RECRUITING");
        assertThat((String) read(created, "$.data.moderationStatus")).isEqualTo("PENDING_REVIEW");

        // ── 5) 조회: OWNER는 200(미승인이어도) / 타인은 404 가시성 게이트 ────
        mvc.perform(get("/api/v1/challenges/" + challengeId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        String otherToken = signupAndLogin("e2e-other", "구경꾼E2E");
        mvc.perform(get("/api/v1/challenges/" + challengeId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        // ── 6) 자동인증: 정규 수동 SELF_CHECK(오늘) → 즉시 SUCCESS ───────────
        MvcResult manual = postJsonAuth("/api/v1/challenges/" + challengeId + "/verifications", ownerToken, """
                {"method":"SELF_CHECK","targetDate":"%s","asFallback":false}""".formatted(today));
        assertThat((String) read(manual, "$.data.status")).isEqualTo("SUCCESS");
        assertThat((String) read(manual, "$.data.verifiedVia")).isEqualTo("MANUAL");
        assertThat((String) read(manual, "$.data.verificationId")).isNotBlank();

        // 진행률 일괄 조회도 정상 동작
        mvc.perform(get("/api/v1/verifications/progress").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // ── 7) 예비 폴백: 제출 → PENDING_APPROVAL → 방장 승인 → SUCCESS ──────
        String tomorrow = LocalDate.now(KST).plusDays(1).toString();
        MvcResult fallback = postJsonAuth("/api/v1/challenges/" + challengeId + "/verifications", ownerToken, """
                {"method":"SELF_CHECK","targetDate":"%s","asFallback":true}""".formatted(tomorrow));
        assertThat((String) read(fallback, "$.data.status")).isEqualTo("PENDING_APPROVAL");
        assertThat((String) read(fallback, "$.data.approvalStatus")).isEqualTo("PENDING");
        String verificationId = read(fallback, "$.data.verificationId");

        MvcResult approved = postJsonAuth(
                "/api/v1/challenges/" + challengeId + "/verifications/" + verificationId + "/approval",
                ownerToken, """
                {"decision":"APPROVE","reason":null}""");
        assertThat((String) read(approved, "$.data.status")).isEqualTo("SUCCESS");
        assertThat((String) read(approved, "$.data.verifiedVia")).isEqualTo("MANUAL_FALLBACK");
    }

    // ===== 헬퍼 =====

    /** 가입 후 로그인까지 해서 accessToken을 돌려준다(부수 사용자 생성용). */
    private String signupAndLogin(String code, String nickname) throws Exception {
        String loginBody = """
                {"code":"%s","codeVerifier":"v","redirectUri":"ruleup://login/callback","deviceInfo":%s}"""
                .formatted(code, DEVICE_INFO);
        String signupToken = read(postJson("/api/v1/auth/oauth/kakao", loginBody), "$.data.signupToken");
        postJson("/api/v1/auth/signup", """
                {"signupToken":"%s","nickname":"%s","interestCategories":["EXERCISE"],
                 "agreements":{"termsOfService":{"agreed":true,"version":"1.0"},
                               "privacyPolicy":{"agreed":true,"version":"1.0"},
                               "marketing":{"agreed":false,"version":"1.0"}},
                 "deviceInfo":%s}""".formatted(signupToken, nickname, DEVICE_INFO));
        return read(postJson("/api/v1/auth/oauth/kakao", loginBody), "$.data.accessToken");
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
    }

    private MvcResult postJsonAuth(String url, String token, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
    }

    @SuppressWarnings("unchecked")
    private <T> T read(MvcResult res, String path) throws Exception {
        return (T) JsonPath.read(res.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }
}
