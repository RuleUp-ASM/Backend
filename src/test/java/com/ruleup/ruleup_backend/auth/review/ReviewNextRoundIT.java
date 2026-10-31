package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import com.ruleup.ruleup_backend.auth.service.TokenService;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.score.service.ScoreService;
import com.ruleup.ruleup_backend.user.UserAccountService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.domain.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ruleup.ruleup_backend.auth.review.ReviewAccountFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 다음 심사 회차 — <b>새 UUID 의 계정</b>과 <b>새 회차 설정</b>으로 바꾼 뒤 새 심사위원이 로그인한다 (심사 계정 테크 스펙 5-2 · 5-8).
 *
 * <p>이 클래스의 설정이 곧 「다음 회차 설정」이다 — {@code round=2029120201}, codes 는 새 계정(NEXT_ROUND_1) 하나와,
 * 운영 실수를 흉내 낸 「지난 회차 계정(JUDGE_8, subject 회차 2029100101)을 새 코드에 다시 묶은 항목」 하나. 설정이 기본과 달라
 * 컨텍스트가 따로 뜬다(DB 컨테이너는 같다).
 *
 * <p>흐름: 지난 회차 심사위원이 JUDGE_8 에 개별 동의·문의를 남긴다 → 일반 탈퇴(평가 종료 자동 탈퇴와 같은 서비스) →
 * 시드가 새 UUID 로 이번 회차 계정을 만든다 → 새 심사위원이 새 코드로 로그인한다. 새 계정은 아무것도 이어받지 않고,
 * 지난 계정은 코드가 맞아도 열리지 않으며, 지난 심사위원의 동의 이력은 그 계정에 그대로 남는다.
 */
@SpringBootTest(properties = {
        "app.review-accounts=01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e08,01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e11",
        "app.review-login.round=2029120201",
        "app.review-login.codes=["
                + "{\"label\":\"next-1\",\"codeHash\":\"5af346426c59cc1b04e34893e2c991b88e47fbf884bbe1cf325443cdaaa08eb6\","
                + "\"userId\":\"01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e11\"},"
                + "{\"label\":\"mistake-old-8\",\"codeHash\":\"c0969c2a8c968d560f2f9bed94c09363c01b473d11161b9f8843df78becf71c5\","
                + "\"userId\":\"01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e08\"}]"
})
@Import(TestcontainersConfiguration.class)
class ReviewNextRoundIT extends AuthApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserRepository userRepository;
    @Autowired ScoreService scoreService;
    @Autowired AgreementService agreementService;
    @Autowired UserAccountService userAccountService;
    @Autowired TokenService tokenService;
    @Autowired AppProperties props;
    @Autowired TransactionTemplate tx;

    MockMvc mvc;
    ReviewAccountFixture fixture;

    @Override protected MockMvc mvc() { return mvc; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        fixture = new ReviewAccountFixture(userRepository, scoreService, agreementService, props, tx, jdbcTemplate);
        fixture.ensure(JUDGE_8);
        fixture.revive(JUDGE_8);
    }

    @AfterEach
    void tearDown() {
        fixture.revive(JUDGE_8);   // 기본 설정의 테스트가 JUDGE_8 을 ACTIVE 로 받는다
    }

    private MvcResult codeLogin(String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/review-login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "198.23.0." + (int) (Math.random() * 200 + 1))
                .content(OM.writeValueAsString(Map.of("code", code)))).andReturn();
    }

    private MvcResult getAuth(String path, String at) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + at)).andReturn();
    }

    private int signalConsentEvents(UUID userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_agreement_events WHERE user_id = ? AND agreement_type IN ('LOCATION_INFO','HEALTH_INFO')",
                Integer.class, bytes(userId));
        return n == null ? 0 : n;
    }

    private int liveRefreshTokens(UUID userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, bytes(userId));
        return n == null ? 0 : n;
    }

    private static byte[] bytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    @Test
    @DisplayName("지난 회차 심사위원의 동의·문의 → 탈퇴 → 새 UUID 계정 시드 → 새 회차 설정으로 새 심사위원 로그인: 아무것도 이어받지 않는다")
    void nextReviewerStartsOnNewAccount() throws Exception {
        // 지난 회차 심사위원(JUDGE_8, subject 회차 2029100101) — 개별 동의 2종과 문의를 남긴다
        User prev = userRepository.findById(JUDGE_8).orElseThrow();
        Instant now = Instant.now();
        agreementService.record(prev, AgreementType.LOCATION_INFO, true, props.client().termsVersions().of(AgreementType.LOCATION_INFO), now);
        agreementService.record(prev, AgreementType.HEALTH_INFO, true, props.client().termsVersions().of(AgreementType.HEALTH_INFO), now);
        String prevAt = tokenService.issueTokenPair(prev).accessToken();
        MvcResult inquiry = mvc.perform(post("/api/v1/inquiries").header("Authorization", "Bearer " + prevAt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("category", "ERROR_ETC",
                        "body", "지난 회차 심사위원이 남긴 문의입니다. 연락처 010-0000-0000")))).andReturn();
        assertThat(inquiry.getResponse().getStatus()).isEqualTo(201);
        int prevEvents = signalConsentEvents(JUDGE_8);
        assertThat(prevEvents).isGreaterThanOrEqualTo(2);

        // 평가 종료 — 종료 스케줄러가 부르는 것과 같은 일반 탈퇴
        userAccountService.withdraw(JUDGE_8, "탈퇴할게요");

        // 다음 회차 준비 — 시드(생성 모드)가 새 UUID 로 이번 회차 계정을 만든다. 지난 계정을 되살려 쓰지 않는다
        assertThat(userRepository.existsById(NEXT_ROUND_1)).as("새 계정은 이 시점에 처음 만들어진다").isFalse();
        User fresh = fixture.ensure(NEXT_ROUND_1);
        assertThat(fresh.getId()).isNotEqualTo(JUDGE_8);
        assertThat(fresh.getOauthSubject()).startsWith("ruleup-review-" + NEXT_ROUND + "-");

        // 새 심사위원 — 새 코드로 새 계정에 들어가고, 지난 사람의 동의·문의는 보이지 않는다
        MvcResult next = codeLogin(CODE_NEXT_1);
        assertThat(next.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) read(next, "$.data.user.id")).isEqualTo(NEXT_ROUND_1.toString());
        assertThat((Boolean) read(next, "$.data.restored")).as("새 계정이라 복원이 아니다").isFalse();
        String nextAt = read(next, "$.data.accessToken");
        Map<String, Object> agreements = read(getAuth("/api/v1/users/me", nextAt), "$.data.agreements");
        assertThat(agreements).doesNotContainKeys("locationInfo", "healthInfo");
        assertThat((List<?>) read(getAuth("/api/v1/inquiries", nextAt), "$.data.items")).isEmpty();

        // 운영 실수 — 지난 회차 계정을 새 설정의 코드에 다시 묶었다. 코드가 맞아도 열리지 않는다
        expectError(codeLogin(CODE_8), 400, "INVALID_REVIEW_CODE");
        assertThat(userRepository.findById(JUDGE_8).orElseThrow().getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(liveRefreshTokens(JUDGE_8)).isZero();

        // 지난 심사위원의 동의 이력은 지우지 않는다 — 탈퇴 계정에 남아 일반 탈퇴 보관 정책을 따른다
        assertThat(signalConsentEvents(JUDGE_8)).isEqualTo(prevEvents);
    }

    @Test
    @DisplayName("지난 회차 계정이 아직 탈퇴되지 않은 채 새 설정에 묶여도 열리지 않는다 — 종료 확인을 빠뜨린 운영 실수")
    void earlierRoundActiveAccountIsRefusedUnderNewConfig() throws Exception {
        assertThat(userRepository.findById(JUDGE_8).orElseThrow().isWithdrawn()).isFalse();

        expectError(codeLogin(CODE_8), 400, "INVALID_REVIEW_CODE");

        assertThat(liveRefreshTokens(JUDGE_8)).isZero();
    }
}
