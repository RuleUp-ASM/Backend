package com.ruleup.ruleup_backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 인증·온보딩 통합 테스트 공통 헬퍼 (요청 바디 조립 · 응답 파싱 · 에러 단언).
 * 로그인/가입 계약 바디가 여러 테스트에 흩어지면 계약이 바뀔 때마다 전부 손봐야 해서 한곳에 모은다.
 */
public abstract class AuthApiSupport {

    /** Spring Boot 4는 Jackson 3를 쓰므로 fasterxml ObjectMapper 는 빈이 아니다 — 직렬화용으로만 직접 생성. */
    protected static final ObjectMapper OM = new ObjectMapper();

    private static final AtomicInteger SEQ = new AtomicInteger();

    protected abstract MockMvc mvc();

    /** 테스트마다 유일한 식별자 — MockOAuthClient 는 code 를 그대로 subject 로 쓴다. */
    protected static String uniq(String prefix) {
        return prefix + System.nanoTime() + "n" + SEQ.incrementAndGet();
    }

    protected static int seq() {
        return SEQ.get();
    }

    protected static Map<String, Object> deviceInfo() {
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

    protected static Map<String, Object> loginBody(String code, String installationId, String deviceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("codeVerifier", "verifier");
        m.put("redirectUri", "kakao://oauth");
        m.put("installationId", installationId);
        m.put("deviceId", deviceId);
        m.put("deviceInfo", deviceInfo());
        return m;
    }

    protected static Map<String, Object> agreementItem(boolean agreed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agreed", agreed);
        m.put("version", "1.0");
        return m;
    }

    /** 필수 3종 동의 + 마케팅 동의, 이벤트·야간 미동의 (계약 예시 기본값). */
    protected static Map<String, Object> allAgreements() {
        Map<String, Object> ag = new LinkedHashMap<>();
        ag.put("termsOfService", agreementItem(true));
        ag.put("privacyPolicy", agreementItem(true));
        ag.put("locationService", agreementItem(true));
        ag.put("marketing", agreementItem(true));
        ag.put("event", agreementItem(false));
        ag.put("nightPush", agreementItem(false));
        return ag;
    }

    protected static Map<String, Object> signupBody(String signupToken, String nickname,
                                                    String installationId, String deviceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signupToken", signupToken);
        m.put("installationId", installationId);
        m.put("nickname", nickname);
        m.put("interestCategories", List.of("EXERCISE", "READING"));
        m.put("birthDate", "2000-05-27");
        m.put("gender", "MALE");
        m.put("agreements", allAgreements());
        m.put("deviceId", deviceId);
        m.put("deviceInfo", deviceInfo());
        return m;
    }

    // ===== 호출 =====

    protected MvcResult postJson(String url, Map<String, Object> body) throws Exception {
        return mvc().perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    protected MvcResult postJsonAuth(String url, String accessToken, Map<String, Object> body) throws Exception {
        return mvc().perform(post(url).header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    /** 신규 로그인 → signupToken (isNewUser=true 확인 포함). */
    protected String issueSignupToken(String code, String installationId, String deviceId) throws Exception {
        MvcResult res = postJson("/api/v1/auth/oauth/kakao", loginBody(code, installationId, deviceId));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) read(res, "$.data.isNewUser")).isTrue();
        return read(res, "$.data.signupToken");
    }

    /** 로그인 → 가입 요청 바디 준비(전송 전). 테스트별로 put/remove 로 변형한다. */
    protected Map<String, Object> preparedSignup(String tag, String nickname) throws Exception {
        String token = issueSignupToken(tag, "inst-" + tag, "dev-" + tag);
        return signupBody(token, nickname, "inst-" + tag, "dev-" + tag);
    }

    /** 가입까지 완료하고 응답을 돌려준다(성공 단언 포함). */
    protected MvcResult signup(String tag, String nickname) throws Exception {
        MvcResult res = postJson("/api/v1/auth/signup", preparedSignup(tag, nickname));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res;
    }

    // ===== 응답 =====

    @SuppressWarnings("unchecked")
    protected <T> T read(MvcResult res, String path) throws Exception {
        return (T) JsonPath.read(res.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    protected void expectError(MvcResult res, int status, String code) throws Exception {
        assertThat(res.getResponse().getStatus()).as("HTTP status").isEqualTo(status);
        assertThat((Boolean) read(res, "$.success")).isFalse();
        assertThat((String) read(res, "$.error.code")).isEqualTo(code);
    }
}
