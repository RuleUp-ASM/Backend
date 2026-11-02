package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.auth.review.ReviewAccountFixture;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.score.service.ScoreService;
import com.ruleup.ruleup_backend.user.UserRepository;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ruleup.ruleup_backend.auth.review.ReviewAccountFixture.CODE_6;
import static com.ruleup.ruleup_backend.auth.review.ReviewAccountFixture.DUMMY;
import static com.ruleup.ruleup_backend.auth.review.ReviewAccountFixture.JUDGE_6;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 심사 계정으로 <b>자동 인증을 실제로 체험할 수 있는가</b> — 코드 로그인부터 sync, 판정까지 (오픈 이슈 #7).
 *
 * <p>sync 는 요청의 {@code deviceId} 를 {@code users.device_id} 와 대조해 다르면 그 요청의 신호를 전부
 * {@code UNTRUSTED_SOURCE} 로 판정에서 뺀다(VerificationSyncService#inactiveDevice). 계정 쪽 값이 비어 있으면
 * 대조할 것이 없어 통과시킨다(관대 모드 — {@code VERIFICATION_REQUIRE_ACTIVE_DEVICE} 기본값 false).
 * 그래서 계약은 <b>시드의 {@code device_id} 를 NULL 로 두고, 코드 로그인은 기기를 받지 않는다</b>이다.
 * 앱은 코드 로그인 때 기기 값을 보낼 필요가 없다. 엄격 모드를 켜면 이 계정의 신호도 빠지므로, 그때 심사 계정
 * 예외를 함께 정해야 한다.
 *
 * <p>평가 대상은 더미 멤버가 방장인 데모 방의 사용 시간 최소(SCREEN_TIME_MIN, 30분) 방이다.
 * 심사 계정은 이 IT 전용(JUDGE_6)이다 — 기기·동의·멤버십을 로그인 IT 와 나누면 실행 순서에 따라 결과가 갈린다.
 * 방마다 고유 패키지를 써서, 앞 테스트가 남긴 방이 이번 판정에 끼지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReviewAccountAutoVerificationIT extends VerificationApiSupport {

    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserRepository userRepository;
    @Autowired ScoreService scoreService;
    @Autowired AgreementService agreementService;
    @Autowired AppProperties props;
    @Autowired TransactionTemplate tx;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        ReviewAccountFixture fixture = new ReviewAccountFixture(userRepository, scoreService, agreementService, props, tx, jdbcTemplate);
        fixture.ensureAll();
        fixture.reviveAll();   // 종료 테스트가 탈퇴시킨 심사 계정을 되살린다
        // 전용 계정(JUDGE_6)을 시드 상태 — device_id NULL, 기기 정보·개별 동의 없음 — 로 되돌리고 시작한다.
        fixture.resetToSeed(JUDGE_6);
        // 개별 동의는 심사위원이 자동 인증을 켤 때 직접 한다 — 여기서는 그 뒤 상태로 시작한다.
        grantSignalConsents(JUDGE_6);
    }

    // ===== 헬퍼 =====

    private MvcResult codeLogin(Map<String, Object> body) throws Exception {
        int n = IP_SEQ.incrementAndGet();
        return mvc.perform(post("/api/v1/auth/review-login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "198.19." + (n / 250) + "." + (n % 250 + 1))
                .content(OM.writeValueAsString(body))).andReturn();
    }

    /** 코드 로그인 → accessToken. deviceId 를 주면 기기 필드를 함께 싣는다(서버는 무시해야 한다). */
    private String loginFrom(String deviceId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", CODE_6);
        if (deviceId != null) {
            body.put("deviceId", deviceId);
            body.put("deviceInfo", deviceInfo());
        }
        MvcResult res = codeLogin(body);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return read(res, "$.data.accessToken");
    }

    /** 심사위원이 참여 중인 데모 자동 인증 방(사용 시간 30분 이상) — 멤버 id. */
    private UUID demoScreenTimeRoom(String packageName) {
        UUID challenge = insertAutoChallenge(DUMMY, "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":30}");
        return insertReadyMember(challenge, JUDGE_6, null, screenApps(packageName));
    }

    private void syncFrom(String token, String deviceId, String packageName, String recordId) throws Exception {
        Map<String, Object> usage = new LinkedHashMap<>(usageSignal(packageName, todayAt(9, 0), todayAt(10, 0)));
        usage.put("recordId", recordId);
        Map<String, Object> body = syncBody(List.of(usage));
        body.put("deviceId", deviceId);
        MvcResult res = postJsonAuth("/api/v1/verifications/sync", token, body);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
    }

    private int untrustedUsageSignals() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM verification_device_usage_signals WHERE userId = ? AND excludeReason = 'UNTRUSTED_SOURCE'",
                Integer.class, (Object) bytes(JUDGE_6));
    }

    // =====================================================================

    @Test
    @DisplayName("코드만 넣고 로그인해도 심사위원 기기에서 보낸 신호가 판정에 쓰인다 — 데모 방 자동 인증이 성공한다")
    void signalsFromJudgeDeviceAreJudged() throws Exception {
        String phone = "judge-phone-" + uniq("");
        String pkg = "com.review.reader." + uniq("p");
        UUID memberId = demoScreenTimeRoom(pkg);
        String token = loginFrom(null);
        int untrustedBefore = untrustedUsageSignals();

        syncFrom(token, phone, pkg, "review-usage-" + uniq(""));

        assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
        assertThat(untrustedUsageSignals()).as("배제된 신호가 없다").isEqualTo(untrustedBefore);
    }

    @Test
    @DisplayName("코드 로그인에 deviceId 를 실어 보내도 저장하지 않는다 — 다른 기기에서 보낸 신호도 판정에 쓰인다")
    void deviceSentAtLoginIsNotPinned() throws Exception {
        String pkg = "com.review.reader." + uniq("p");
        UUID memberId = demoScreenTimeRoom(pkg);
        String token = loginFrom("judge-phone-a-" + uniq(""));

        syncFrom(token, "judge-phone-b-" + uniq(""), pkg, "review-usage-" + uniq(""));

        assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject("SELECT device_id FROM users WHERE id = ?", String.class,
                (Object) bytes(JUDGE_6))).isNull();
    }

    @Test
    @DisplayName("시드에 device_id 를 넣으면 심사위원 기기 신호가 전부 빠진다 — 시드가 NULL 이어야 하는 이유")
    void seededDeviceIdRejectsJudgeSignals() throws Exception {
        String pkg = "com.review.reader." + uniq("p");
        UUID memberId = demoScreenTimeRoom(pkg);
        jdbcTemplate.update("UPDATE users SET device_id = 'review-dev-06' WHERE id = ?", (Object) bytes(JUDGE_6));
        String token = loginFrom(null);
        int untrustedBefore = untrustedUsageSignals();

        syncFrom(token, "judge-phone-" + uniq(""), pkg, "review-usage-" + uniq(""));

        assertThat(todayStatusOf(memberId)).isEqualTo("PENDING");
        assertThat(untrustedUsageSignals()).isGreaterThan(untrustedBefore);
    }
}
