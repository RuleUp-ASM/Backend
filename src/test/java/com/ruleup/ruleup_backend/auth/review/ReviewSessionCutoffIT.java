package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.TestcontainersConfiguration.MutableClock;
import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.score.service.ScoreService;
import com.ruleup.ruleup_backend.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.ruleup.ruleup_backend.auth.review.ReviewAccountFixture.CODE_5;
import static com.ruleup.ruleup_backend.auth.review.ReviewAccountFixture.EXPIRES_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 끊김 보장 「꺼진 시각 + AT 수명」을 <b>실제 만료로</b> 확인한다 (심사 계정 테크 스펙 5-7 · 최대 30분).
 *
 * <p>운영 AT 수명은 30분이라 그대로 기다릴 수 없다. 이 컨텍스트만 AT 수명을 {@value #TTL_SEC}초로 줄여,
 * 「꺼지기 직전에 재발급한 마지막 AT」가 꺼진 시각 + 수명 안에 실제로 만료되고, 그 뒤 보호 API 가 401 인지 본다.
 * 끊김 보장은 종료 스케줄러 주기와 무관하다 — 꺼지는 순간부터 재발급이 막히므로, 꺼지기 직전에 받은 AT 가
 * 받을 수 있는 마지막 AT 다. 그래서 스케줄러는 이 테스트에서 돌리지 않는다.
 *
 * <h4>시계 두 개</h4>
 * 스위치는 {@link MutableClock}(시계 빈)으로, JWT 발급·검증은 시스템 시계로 판정한다. JwtProvider 를 시계 빈에
 * 묶으면 시계를 앞당기는 다른 IT 들의 토큰이 그 순간 만료돼 버린다. 그래서 「꺼진 시각」을 두 시계가 함께
 * 지나는 한 순간 — 시계 빈을 만료 뒤로 넘기는 바로 그 호출 — 으로 잡고, 만료는 시스템 시계로 실제로 기다린다.
 *
 * <h4>「꺼진 시각」</h4>
 * <ul>
 *   <li>자연 만료: {@code expiresAt}. 한 태스크 안에서 경계가 정확해야 한다 — 스위치 확인을 통과한 요청이 지연돼
 *       꺼진 뒤 토큰을 만들 수 있으므로, <b>발급한 뒤 스위치를 다시 확인하고 꺼졌으면 발급분을 회수하고 거부</b>한다.
 *       그러면 전달되는 AT 는 모두 꺼지기 전에 만들어진 것이라 만료가 「꺼진 시각 + AT 수명」을 넘지 않는다.
 *       아래 경합 테스트는 스위치 확인 직후 요청을 멈추고(1.2초) 그 사이에 끈다</li>
 *   <li>긴급 차단(enabled=false 재배포): 설정을 부팅 때 읽으므로 구 태스크가 요청을 받는 동안은 켜져 있다 —
 *       꺼진 시각은 롤링 배포가 끝나 구 태스크가 요청을 더 받지 않는 시각이다(ALB 등록 해제 지연 60초 포함).
 *       태스크 사이 경계라 이 테스트의 범위 밖이고 스펙에 정의로 둔다</li>
 * </ul>
 *
 * <p>경합 테스트는 {@link ReviewAccountPolicy#isSwitchOn()} 을 스파이로 감싼다 — 판정이 스위치를 읽는 곳은 이것
 * 하나라는 계약이다(isCodeSessionEnded 도 안에서 이것을 부른다).
 */
@SpringBootTest(properties = "app.jwt.access-token-ttl=" + ReviewSessionCutoffIT.TTL_SEC)
@Import(TestcontainersConfiguration.class)
class ReviewSessionCutoffIT extends AuthApiSupport {

    static final int TTL_SEC = 3;
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired ScoreService scoreService;
    @Autowired AgreementService agreementService;
    @Autowired AppProperties props;
    @Autowired TransactionTemplate tx;
    @Autowired MutableClock clock;
    @MockitoSpyBean ReviewAccountPolicy policy;
    @Autowired com.ruleup.ruleup_backend.security.JwtProvider jwtProvider;

    MockMvc mvc;
    /** 켜 두면 다음 isSwitchOn() 이 「켜짐」을 돌려준 직후 스위치를 끄고 요청을 멈춘다 — 확인과 발급 사이의 경합. */
    private final AtomicBoolean pauseAfterNextCheck = new AtomicBoolean();
    private final AtomicReference<Instant> offAt = new AtomicReference<>();

    @Override protected MockMvc mvc() { return mvc; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        ReviewAccountFixture fixture = new ReviewAccountFixture(userRepository, scoreService, agreementService, props, tx, jdbc);
        fixture.ensureAll();
        fixture.reviveAll();   // 종료 테스트가 탈퇴시킨 심사 계정을 되살린다
        fixture.resetToSeed(ReviewAccountFixture.JUDGE_5);
        doAnswer(inv -> {
            Object on = inv.callRealMethod();
            if (Boolean.TRUE.equals(on) && pauseAfterNextCheck.getAndSet(false)) {
                offAt.set(switchOff());
                Thread.sleep(1_200);   // 확인을 통과한 요청이 지연되는 동안 스위치가 꺼진다
            }
            return on;
        }).when(policy).isSwitchOn();
    }

    @AfterEach
    void resetClock() {
        pauseAfterNextCheck.set(false);
        clock.reset();
    }

    private Instant expOf(String accessToken) {
        return jwtProvider.parse(accessToken).getExpiration().toInstant();
    }

    /**
     * 이 RT 가 속한 세션(family)에 아직 살아 있는 RT 수 — 거절한 요청이 회수를 <b>커밋했는지</b> 본다.
     * 스케줄러도 추가 재발급도 부르지 않은 상태에서 읽어야, 뒤따르는 요청이 대신 회수해 준 것과 구분된다.
     */
    private int liveInFamilyOf(String rt) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NULL AND family_id = " +
                        "(SELECT family_id FROM refresh_tokens WHERE token_hash = ?)",
                Integer.class, (Object) com.ruleup.ruleup_backend.auth.service.TokenService.sha256(rt));
    }

    /** JUDGE_5 의 살아 있는 RT id 들 — 응답에 실리지 않은 발급분까지 본다. */
    private java.util.Set<String> liveRtIdsOfJudge5() {
        return new java.util.HashSet<>(jdbc.queryForList(
                "SELECT HEX(id) FROM refresh_tokens WHERE user_id = UUID_TO_BIN(?) AND revoked_at IS NULL",
                String.class, ReviewAccountFixture.JUDGE_5.toString()));
    }

    private MvcResult refresh(String rt) throws Exception {
        return postJson("/api/v1/auth/refresh", Map.of("refreshToken", rt));
    }

    private MvcResult codeLogin() throws Exception {
        int n = IP_SEQ.incrementAndGet();
        return mvc.perform(post("/api/v1/auth/review-login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "198.20." + (n / 250) + "." + (n % 250 + 1))
                .content(OM.writeValueAsString(Map.of("code", CODE_5)))).andReturn();
    }

    private int me(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getStatus();
    }

    /** 시계 빈을 expiresAt 뒤로 넘긴다 — 스위치가 꺼지는 순간. 그 순간의 시스템 시각을 돌려준다. */
    private Instant switchOff() {
        clock.advance(Duration.between(clock.instant(), EXPIRES_AT).plusMinutes(1));
        return Instant.now();
    }

    @Test
    @DisplayName("꺼지기 직전에 재발급한 마지막 AT 는 꺼진 시각 + AT 수명 안에 실제로 만료되고, 그 뒤 보호 API 는 401")
    void lastAccessTokenActuallyExpiresWithinGuarantee() throws Exception {
        MvcResult login = codeLogin();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(props.jwt().accessTokenTtl()).as("이 컨텍스트의 AT 수명").isEqualTo(TTL_SEC);

        // 최악의 경우: 꺼지기 바로 직전에 재발급한다.
        MvcResult rotated = postJson("/api/v1/auth/refresh", Map.of("refreshToken", read(login, "$.data.refreshToken")));
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String lastAt = read(rotated, "$.data.accessToken");
        Instant off = switchOff();

        // 꺼진 뒤로는 새 AT 를 받을 길이 없다 — 재발급도, 코드 로그인도.
        expectError(postJson("/api/v1/auth/refresh", Map.of("refreshToken", read(rotated, "$.data.refreshToken"))),
                401, "SESSION_EXPIRED");
        assertThat(codeLogin().getResponse().getStatus()).isEqualTo(404);
        assertThat(me(lastAt)).as("이미 받은 AT 는 남은 수명 동안 쓴다").isEqualTo(200);

        // 실제로 만료될 때까지 기다린다. exp 는 초 단위로 잘리므로 1초 여유를 둔다.
        Instant deadline = off.plusSeconds(TTL_SEC).plusSeconds(1);
        Instant cutAt = null;
        while (Instant.now().isBefore(deadline.plusSeconds(2))) {
            if (me(lastAt) == 401) { cutAt = Instant.now(); break; }
            Thread.sleep(100);
        }

        assertThat(cutAt).as("AT 가 실제로 만료돼 보호 API 가 401 이 된다").isNotNull();
        assertThat(cutAt).as("끊긴 시각 ≤ 꺼진 시각 + AT 수명").isBeforeOrEqualTo(deadline);
        assertThat(me(lastAt)).as("한 번 끊기면 계속 끊긴다").isEqualTo(401);
    }

    @Test
    @DisplayName("꺼진 뒤 다시 켜도, 꺼져 있는 동안 거절된 RT 로는 이어지지 않는다 — 종료 스케줄러가 돌기 전이라도")
    void refusedRefreshDoesNotResumeAfterReEnable() throws Exception {
        String rt = read(codeLogin(), "$.data.refreshToken");
        switchOff();
        expectError(postJson("/api/v1/auth/refresh", Map.of("refreshToken", rt)), 401, "SESSION_EXPIRED");
        assertThat(liveInFamilyOf(rt)).as("거절한 요청이 그 세션의 RT 를 회수해 커밋했다").isZero();

        clock.reset();   // 다시 켜짐

        expectError(postJson("/api/v1/auth/refresh", Map.of("refreshToken", rt)), 401, "SESSION_EXPIRED");
        assertThat(codeLogin().getResponse().getStatus()).as("코드를 다시 넣으면 된다").isEqualTo(200);
    }

    @Test
    @DisplayName("회전 유예 중인 이전 RT 를 꺼진 뒤 제출하면 family 전체가 끊긴다 — 다시 켜도 이전·후속 RT 모두 401")
    void rotationGraceDoesNotSurviveSwitchOff() throws Exception {
        String rt1 = read(codeLogin(), "$.data.refreshToken");
        String rt2 = read(refresh(rt1), "$.data.refreshToken");   // rt1 은 방금 회전돼 유예(30초) 안이다
        switchOff();

        // 스위치 확인이 회전 유예 분기보다 먼저다 — 유예로 새 페어가 나가지 않는다.
        expectError(refresh(rt1), 401, "SESSION_EXPIRED");
        assertThat(liveInFamilyOf(rt1)).as("후속 RT(rt2)까지 family 전체가 회수됐다 — 스케줄러 없이").isZero();

        clock.reset();   // 스케줄러가 돌기 전에 다시 켜짐

        expectError(refresh(rt1), 401, "SESSION_EXPIRED");
        expectError(refresh(rt2), 401, "SESSION_EXPIRED");
    }

    @Test
    @DisplayName("재발급이 스위치 확인을 통과한 직후 지연되고 그 사이 꺼져도, 전달된 AT 는 꺼진 시각 + AT 수명을 넘지 않는다")
    void refreshRacingSwitchOffStaysWithinGuarantee() throws Exception {
        String rt = read(codeLogin(), "$.data.refreshToken");
        pauseAfterNextCheck.set(true);

        MvcResult res = refresh(rt);

        Instant off = offAt.get();
        assertThat(off).as("경합이 실제로 일어났다").isNotNull();
        if (res.getResponse().getStatus() == 200) {
            assertThat(expOf(read(res, "$.data.accessToken"))).isBeforeOrEqualTo(off.plusSeconds(TTL_SEC));
        } else {
            expectError(res, 401, "SESSION_EXPIRED");
        }
        // 경합 요청이 만든 RT 까지 그 요청 안에서 회수돼 커밋됐다 — 스케줄러·추가 요청 없이 DB 로 본다.
        assertThat(liveInFamilyOf(rt)).as("경합 요청 직후 세션에 살아 있는 RT").isZero();

        clock.reset();   // 다시 켜도 그 세션으로는 이어지지 않는다
        expectError(refresh(rt), 401, "SESSION_EXPIRED");
        if (res.getResponse().getStatus() == 200) {
            expectError(refresh(read(res, "$.data.refreshToken")), 401, "SESSION_EXPIRED");
        }
    }

    @Test
    @DisplayName("코드 로그인이 스위치 확인을 통과한 직후 지연되고 그 사이 꺼져도, 전달된 AT 는 꺼진 시각 + AT 수명을 넘지 않는다")
    void codeLoginRacingSwitchOffStaysWithinGuarantee() throws Exception {
        java.util.Set<String> liveBefore = liveRtIdsOfJudge5();
        pauseAfterNextCheck.set(true);

        MvcResult res = codeLogin();

        Instant off = offAt.get();
        assertThat(off).as("경합이 실제로 일어났다").isNotNull();
        if (res.getResponse().getStatus() == 200) {
            assertThat(expOf(read(res, "$.data.accessToken"))).isBeforeOrEqualTo(off.plusSeconds(TTL_SEC));
        } else {
            assertThat(res.getResponse().getStatus()).as("꺼진 뒤의 코드 로그인과 같은 404").isEqualTo(404);
        }
        // 응답에 실리지 않은 발급분까지 그 요청 안에서 회수돼 커밋됐다 — 새로 살아남은 RT 가 없다.
        java.util.Set<String> created = liveRtIdsOfJudge5();
        created.removeAll(liveBefore);
        assertThat(created).as("경합 요청이 남긴 살아 있는 RT").isEmpty();

        if (res.getResponse().getStatus() == 200) {
            clock.reset();
            expectError(refresh(read(res, "$.data.refreshToken")), 401, "SESSION_EXPIRED");
        }
    }
}
