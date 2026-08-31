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
    //Map 형태를 Json으로 바꿔주는 역할
    protected static final ObjectMapper OM = new ObjectMapper();

    // 테스트 병렬 진행을 할 수 있게 동시성 보장
    private static final AtomicInteger SEQ = new AtomicInteger();

    // 테스트용 라이브러리
    protected abstract MockMvc mvc();

    /** 테스트마다 유일한 식별자 — MockOAuthClient 는 code 를 그대로 subject 로 쓴다. */
    protected static String uniq(String prefix) {
        return prefix + System.nanoTime() + "n" + SEQ.incrementAndGet();
    }

    protected static int seq() {
        return SEQ.get();
    }

    // 디바이스 정보 입력
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

    // 로그인 정보 입력
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

    /**
     * 약관 5종 — 필수 3종 동의 + 마케팅 동의, 이벤트 미동의 (계약 예시 기본값).
     * 야간 푸시 동의 약관은 2026-08-28 폐지됐고, 법정 개별 동의 2종은 가입이 아니라
     * POST /api/v1/users/me/agreements 로 받는다.
     */
    protected static Map<String, Object> allAgreements() {
        Map<String, Object> ag = new LinkedHashMap<>();
        ag.put("termsOfService", agreementItem(true));
        ag.put("privacyPolicy", agreementItem(true));
        ag.put("locationService", agreementItem(true));
        ag.put("marketing", agreementItem(true));
        ag.put("event", agreementItem(false));
        return ag;
    }

    // 회원가입 정보 입력
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

    // ===== 제재 (테스트 보조) =====

    /**
     * 제재를 거는 통로. 예전에는 테스트가 {@code user.lock()} 처럼 상태값을 직접 바꿨는데,
     * 이제 정지의 종류·기간은 {@code sanctions} 가 소유하고 {@code users.status} 전이는 그와
     * <b>같은 트랜잭션</b>이어야 한다. 둘 중 하나만 하면 게이트가 스스로 되돌려 버리므로
     * 운영 경로와 같은 서비스를 거친다.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    protected com.ruleup.ruleup_backend.sanction.SanctionService sanctionService;

    protected com.ruleup.ruleup_backend.sanction.SanctionService sanctions() {
        return sanctionService;
    }

    protected void lock(java.util.UUID userId) {
        sanctions().impose(userId,
                com.ruleup.ruleup_backend.sanction.domain.SanctionTrack.DISCRETIONARY,
                com.ruleup.ruleup_backend.sanction.domain.SanctionType.LOCK, null,
                com.ruleup.ruleup_backend.sanction.domain.SanctionReason.REPORT_CONFIRMED,
                "테스트 잠금",
                com.ruleup.ruleup_backend.sanction.domain.SanctionSource.DIRECT, null, null,
                java.time.Instant.now().plus(java.time.Duration.ofDays(30)));
    }

    protected void ban(java.util.UUID userId) {
        sanctions().impose(userId,
                com.ruleup.ruleup_backend.sanction.domain.SanctionTrack.DISCRETIONARY,
                com.ruleup.ruleup_backend.sanction.domain.SanctionType.BAN, null,
                com.ruleup.ruleup_backend.sanction.domain.SanctionReason.ILLEGAL_CONTENT,
                "테스트 영구 정지",
                com.ruleup.ruleup_backend.sanction.domain.SanctionSource.DIRECT, null, null, null);
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
