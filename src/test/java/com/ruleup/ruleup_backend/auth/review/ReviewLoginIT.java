package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.TestcontainersConfiguration.MutableClock;
import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import com.ruleup.ruleup_backend.auth.service.TokenService;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.score.service.ScoreService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.domain.UserStatus;
import com.ruleup.ruleup_backend.verification.service.DeviceSyncPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ruleup.ruleup_backend.auth.review.ReviewAccountFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 심사 코드 로그인 {@code POST /api/v1/auth/review-login} 과 심사 기능 종료
 * (심사 계정 테크 스펙 5-2 · 5-4 · 5-5 유형 2·3 · 5-7 · 오픈 이슈 #7).
 *
 * <ol>
 *   <li>성공 응답은 기존 회원 로그인 DTO 그대로다</li>
 *   <li><b>기기 계약(오픈 이슈 #7 확정)</b> — {@code deviceId}·{@code deviceInfo}·{@code installationId} 는
 *       보내도 무시하고 응답 {@code device} 는 null 이다. 시드의 {@code device_id} 가 NULL 이라 sync 의 활성
 *       기기 대조를 건너뛴다 — 자동 인증은 그것으로 된다(ReviewAccountAutoVerificationIT).
 *       단일 활성 기기 revoke·세션 종료 알림과 설치 인계도 없다</li>
 *   <li>불일치 400 {@code INVALID_REVIEW_CODE} (401 아님) · 시도 초과 429 {@code TOO_MANY_ATTEMPTS} + Retry-After</li>
 *   <li>탈퇴한 심사 계정은 코드로 복원 · 정지는 기존 게이트대로 403</li>
 *   <li>푸시는 보내지 않는다 — 코드 계정의 푸시 토큰 등록은 200 으로 받고 저장하지 않으며, 그 계정에 남은 활성 토큰은
 *       그때 내린다. 그래서 이전부터 앱을 쓰던 심사위원의 본인 계정 토큰이 심사 계정으로 넘어가지 않는다.
 *       Play 심사 계정·더미 멤버는 기존대로다</li>
 *   <li>코드 폐기는 항목을 지우지 않고 {@code "revoked": true} 로 바꾼다 — 로그인은 400, 기존 세션은 스위치와 무관하게 끊긴다</li>
 *   <li>스위치가 꺼지면 404 이고 <b>재발급도 그 즉시 401</b> {@code SESSION_EXPIRED} 다 — 끊김 보장은
 *       「꺼진 시각 + AT 수명(30분)」으로 종료 스케줄러 주기와 무관하다. 종료 스케줄러는 {@code codes} 계정의 RT 를
 *       회수해 스위치를 다시 켜도 옛 세션이 살아나지 않게 정리하고, <b>codes 계정을 기존 탈퇴 흐름으로 탈퇴시킨다</b>
 *       (방 나가기·봇 방장 전환까지 일반 탈퇴와 같다 — ReviewAccountShutdownIT). 다음 심사 때 코드로 로그인하면 복원된다. 실제 AT 만료 경계는 ReviewSessionCutoffIT,
 *       실제 주기·기동 직후 실행은 ReviewSessionTerminatorSchedulingTest 가 본다</li>
 * </ol>
 *
 * <h4>공유 계정</h4>
 * 심사 계정 행은 스위트 전체가 함께 쓴다. 매 테스트 JUDGE_1·2·5 를 시드 상태(기기 값·기기 정보·개별 동의)로
 * 되돌린다 — 「기기 값은 무시한다」를 보려면 앞 테스트가 무엇을 남겼든 시드(NULL)에서 시작해야 한다.
 * 탈퇴(JUDGE_3)·정지(JUDGE_4)는 되돌리지 않는 전용 계정이다.
 *
 * <p>시도 제한이 IP 로 세므로 요청마다 서로 다른 {@code X-Forwarded-For} 를 붙인다 — 안 그러면 실패를
 * 일부러 내는 테스트들이 127.0.0.1 한 칸을 함께 채워 서로를 429 로 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReviewLoginIT extends AuthApiSupport {

    private static final String URL = "/api/v1/auth/review-login";
    private static final AtomicInteger IP_SEQ = new AtomicInteger();


    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired ScoreService scoreService;
    @Autowired AgreementService agreementService;
    @Autowired AppProperties props;
    @Autowired TransactionTemplate tx;
    @Autowired TokenService tokenService;
    @Autowired DeviceSyncPolicyService syncPolicy;
    @Autowired MutableClock clock;
    @Autowired ReviewSessionTerminator terminator;

    MockMvc mvc;
    ReviewAccountFixture fixture;

    @Override protected MockMvc mvc() { return mvc; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        fixture = new ReviewAccountFixture(userRepository, scoreService, agreementService, props, tx, jdbc);
        fixture.ensureAll();
        fixture.reviveAll();   // 종료 테스트가 탈퇴시킨 심사 계정을 되살린다
        for (UUID judge : List.of(JUDGE_1, JUDGE_2, JUDGE_5)) fixture.resetToSeed(judge);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ===== 헬퍼 =====

    /** 요청마다 새 IP — ALB 가 붙인 마지막 값이 시도 제한 키다. */
    private static String freshIp() {
        int n = IP_SEQ.incrementAndGet();
        return "198.18." + (n / 250) + "." + (n % 250 + 1);
    }

    private MvcResult reviewLogin(Map<String, Object> body, String forwardedFor) throws Exception {
        return mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", forwardedFor)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    private MvcResult reviewLogin(String code) throws Exception {
        return reviewLogin(codeBody(code), freshIp());
    }

    /** 심사위원 기기에서 보내는 요청 — 코드 + 그 기기의 deviceId·deviceInfo. */
    private MvcResult reviewLoginFrom(String code, String deviceId) throws Exception {
        Map<String, Object> body = codeBody(code);
        body.put("deviceId", deviceId);
        body.put("deviceInfo", deviceInfo());
        return reviewLogin(body, freshIp());
    }

    /**
     * 겹치지 않는 닉네임(접두 + 숫자 8자리, 정책 2~12자). uniq 의 <b>뒤쪽</b>(나노초 하위 자리 + 순번)을 쓴다 —
     * 앞쪽은 나노초 상위 자리라 같은 1초 안의 가입끼리 같아져 닉네임 중복 409 로 가입이 실패한다.
     */
    private static String nickname(String prefix) {
        String digits = uniq("").replace("n", "");
        return prefix + digits.substring(digits.length() - 8);
    }

    private static Map<String, Object> codeBody(String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        return m;
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return postJson("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken));
    }

    private MvcResult me(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken)).andReturn();
    }

    private MvcResult registerPushToken(String accessToken, String fcmToken) throws Exception {
        return postJsonAuth("/api/v1/devices", accessToken, Map.of("token", fcmToken, "platform", "ANDROID"));
    }

    private User reload(UUID id) {
        return userRepository.findById(id).orElseThrow();
    }

    private int activeRefreshTokens(UUID userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = UUID_TO_BIN(?) AND revoked_at IS NULL",
                Integer.class, userId.toString());
    }

    /** 이 FCM 토큰이 지금 어느 계정의 활성 토큰인가(없으면 null). */
    private UUID activePushOwner(String fcmToken) {
        List<String> owners = jdbc.queryForList(
                "SELECT BIN_TO_UUID(userId) FROM DeviceToken WHERE token = ? AND isActive = 1", String.class, fcmToken);
        return owners.isEmpty() ? null : UUID.fromString(owners.get(0));
    }

    /** 이 RT 가 속한 세션(family)에 살아 있는 RT 수 — 거절한 재발급이 회수를 커밋했는지 본다(스케줄러 없이). */
    private int liveInFamilyOf(String rt) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NULL AND family_id = " +
                        "(SELECT family_id FROM refresh_tokens WHERE token_hash = ?)",
                Integer.class, (Object) TokenService.sha256(rt));
    }

    private int activePushTokens(UUID userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM DeviceToken WHERE userId = UUID_TO_BIN(?) AND isActive = 1",
                Integer.class, userId.toString());
    }

    private static String fcmToken() {
        return "fcm-review-" + uniq("t") + "-APA91bH0aBcDeFgHiJkLmNoPqRsTuVwXyZ";
    }

    /** 스위치를 끈다 — expiresAt 을 넘기도록 시계를 민다. {@link #resetClock()} 이 되돌린다. */
    private void passExpiry() {
        clock.advance(Duration.between(clock.instant(), EXPIRES_AT).plusMinutes(1));
    }

    // =====================================================================
    @Nested
    @DisplayName("성공 — 기존 회원 로그인과 같은 응답")
    class Success {

        @Test
        @DisplayName("코드만 보내면 200 — 기존 회원 200 응답과 같은 구조이고 device 는 null, sync 주기는 폴백 값")
        void returnsExistingMemberLoginShape() throws Exception {
            MvcResult res = reviewLogin(CODE_1);

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.success")).isTrue();
            assertThat((Boolean) read(res, "$.data.isNewUser")).isFalse();
            assertThat((Boolean) read(res, "$.data.restored")).isFalse();
            assertThat((Boolean) read(res, "$.data.returningUser")).as("스펙 응답 예시는 false").isFalse();
            assertThat((String) read(res, "$.data.accessToken")).isNotBlank();
            assertThat((String) read(res, "$.data.refreshToken")).isNotBlank();
            assertThat((String) read(res, "$.data.tokenType")).isEqualTo("Bearer");
            assertThat(((Number) read(res, "$.data.expiresIn")).longValue())
                    .isEqualTo(props.jwt().accessTokenTtl());
            assertThat((Integer) read(res, "$.data.flushIntervalSec"))
                    .as("기기 정보가 없을 때의 폴백 행(priority 0) 값")
                    .isEqualTo(syncPolicy.resolve(Map.of()));
            assertThat((Object) read(res, "$.data.device")).as("받은 기기 정보가 없다").isNull();
            assertThat((Object) read(res, "$.data.signupToken")).isNull();

            assertThat((String) read(res, "$.data.user.id")).isEqualTo(JUDGE_1.toString());
            assertThat((String) read(res, "$.data.user.nickname")).isEqualTo("룰업심사01");
            assertThat((String) read(res, "$.data.user.nicknameStatus")).isEqualTo("APPROVED");
            assertThat((String) read(res, "$.data.user.provider")).isEqualTo("GOOGLE");
            assertThat((String) read(res, "$.data.user.accountStatus")).isEqualTo("ACTIVE");
            assertThat((Boolean) read(res, "$.data.user.onboardingCompleted")).isTrue();
        }

        @Test
        @DisplayName("소문자·공백·하이픈 없이 쳐도 같은 코드다")
        void acceptsUnnormalizedInput() throws Exception {
            MvcResult res = reviewLogin("  k7qm 4xrd9wtf ");

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.user.id")).isEqualTo(JUDGE_1.toString());
        }

        @Test
        @DisplayName("받은 토큰으로 보호 API 를 쓰고 재발급할 수 있다 — 재발급 주기도 로그인과 같다")
        void issuedTokensWork() throws Exception {
            MvcResult login = reviewLogin(CODE_1);
            String at = read(login, "$.data.accessToken");
            String rt = read(login, "$.data.refreshToken");

            MvcResult me = me(at);
            assertThat(me.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(me, "$.data.user.id")).isEqualTo(JUDGE_1.toString());

            MvcResult refreshed = refresh(rt);
            assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
            assertThat((Integer) read(refreshed, "$.data.flushIntervalSec"))
                    .isEqualTo((Integer) read(login, "$.data.flushIntervalSec"));
        }

        @Test
        @DisplayName("last_login_at 을 갱신한다")
        void touchesLastLogin() throws Exception {
            Instant before = Instant.now().minusSeconds(1);

            assertThat(reviewLogin(CODE_1).getResponse().getStatus()).isEqualTo(200);

            assertThat(reload(JUDGE_1).getLastLoginAt()).isAfter(before);
        }

        @Test
        @DisplayName("심사위원 둘이 동시에 써도 서로의 세션에 영향이 없다 — 1인 1계정")
        void judgesAreIndependent() throws Exception {
            MvcResult first = reviewLoginFrom(CODE_1, "judge1-phone-" + uniq(""));
            MvcResult second = reviewLoginFrom(CODE_2, "judge2-phone-" + uniq(""));

            assertThat((String) read(second, "$.data.user.id")).isEqualTo(JUDGE_2.toString());
            assertThat(refresh(read(first, "$.data.refreshToken")).getResponse().getStatus()).isEqualTo(200);
            assertThat(refresh(read(second, "$.data.refreshToken")).getResponse().getStatus()).isEqualTo(200);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("기기 계약 — deviceId·deviceInfo·installationId 는 무시, device 는 null(오픈 이슈 #7)")
    class DeviceContract {

        @Test
        @DisplayName("기기 필드를 보내도 저장하지 않는다 — device_id 는 시드의 NULL, 기기 스펙도 비어 있다")
        void ignoresDeviceIdAndInfo() throws Exception {
            MvcResult res = reviewLoginFrom(CODE_1, "judge-phone-" + uniq(""));

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            User judge = reload(JUDGE_1);
            assertThat(judge.getDeviceId()).as("활성 기기 대조를 건너뛰게 비워 둔다").isNull();
            assertThat(judge.getDeviceModel()).isNull();
            assertThat((Object) read(res, "$.data.device")).isNull();
            assertThat((Integer) read(res, "$.data.flushIntervalSec"))
                    .as("기기 스펙이 없으니 sync 주기는 폴백 값").isEqualTo(syncPolicy.resolve(Map.of()));
        }

        @Test
        @DisplayName("installationId 는 보내도 무시한다 — 계정의 고정 설치 값이 그대로다")
        void ignoresInstallationId() throws Exception {
            Map<String, Object> body = codeBody(CODE_1);
            body.put("deviceId", "judge-phone-" + uniq(""));
            body.put("installationId", "judge-inst-" + uniq(""));
            body.put("permissions", Map.of("location", true));

            assertThat(reviewLogin(body, freshIp()).getResponse().getStatus()).isEqualTo(200);

            assertThat(reload(JUDGE_1).getInstallationId()).isEqualTo("review-inst-2029120101-01");
        }

        @Test
        @DisplayName("다른 기기로 다시 로그인해도 이전 세션을 끊지 않는다 — 단일 활성 기기 revoke·세션 종료 알림이 없다")
        void doesNotRevokePreviousSession() throws Exception {
            String firstRt = read(reviewLoginFrom(CODE_1, "phone-a-" + uniq("")), "$.data.refreshToken");

            assertThat(reviewLoginFrom(CODE_1, "phone-b-" + uniq("")).getResponse().getStatus()).isEqualTo(200);

            assertThat(refresh(firstRt).getResponse().getStatus()).isEqualTo(200);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE user_id = UUID_TO_BIN(?) AND type = 'DEVICE_LOGGED_OUT'",
                    Integer.class, JUDGE_1.toString()))
                    .as("세션 종료 알림도 보내지 않는다").isZero();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("이전부터 앱을 쓰던 심사위원 — 본인 계정이 이미 그 기기에 있다")
    class ExistingAppUser {

        /** 심사위원 본인의 일반 계정. 같은 기기(inst-tag·dev-tag)로 가입해 쓰던 중이다. */
        private record OwnAccount(String tag, UUID id, String accessToken, String refreshToken) {
            String installationId() { return "inst-" + tag; }
            String deviceId() { return "dev-" + tag; }
        }

        private OwnAccount ownAccount() throws Exception {
            String tag = uniq("rv-own");
            MvcResult res = signup(tag, nickname("본인"));
            return new OwnAccount(tag, UUID.fromString(read(res, "$.data.user.id")),
                    read(res, "$.data.accessToken"), read(res, "$.data.refreshToken"));
        }

        private MvcResult reviewLoginOnSameDevice(OwnAccount own, String code) throws Exception {
            Map<String, Object> body = codeBody(code);
            body.put("installationId", own.installationId());
            body.put("deviceId", own.deviceId());
            body.put("deviceInfo", deviceInfo());
            return reviewLogin(body, freshIp());
        }

        @Test
        @DisplayName("같은 기기에서 코드로 들어와도 본인 계정의 설치·기기·세션은 그대로다 — 설치 인계·다계정 차단이 없다")
        void ownAccountUntouched() throws Exception {
            OwnAccount own = ownAccount();

            MvcResult res = reviewLoginOnSameDevice(own, CODE_1);

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            User normal = reload(own.id());
            assertThat(normal.getInstallationId()).isEqualTo(own.installationId());
            assertThat(normal.getDeviceId()).isEqualTo(own.deviceId());
            assertThat(normal.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(refresh(own.refreshToken()).getResponse().getStatus()).isEqualTo(200);

            User judge = reload(JUDGE_1);
            assertThat(judge.getDeviceId()).as("심사 계정은 기기를 받지 않는다").isNull();
            assertThat(judge.getInstallationId()).isEqualTo("review-inst-2029120101-01");
        }

        @Test
        @DisplayName("심사가 끝나 본인 계정으로 돌아가도 막히지 않고, 심사 세션도 끊지 않는다")
        void backToOwnAccount() throws Exception {
            OwnAccount own = ownAccount();
            String judgeRt = read(reviewLoginOnSameDevice(own, CODE_1), "$.data.refreshToken");

            MvcResult back = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(own.tag(), own.installationId(), own.deviceId()));

            assertThat(back.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(back, "$.data.isNewUser")).isFalse();
            assertThat((String) read(back, "$.data.user.id")).isEqualTo(own.id().toString());
            assertThat(refresh(judgeRt).getResponse().getStatus())
                    .as("본인 계정 로그인이 심사 계정 세션을 끊지 않는다 — 설치 보유자가 아니다").isEqualTo(200);
        }

        @Test
        @DisplayName("심사 계정의 푸시 토큰 등록은 200 으로 받고 저장하지 않는다 — 본인 계정 토큰이 그대로 남는다")
        void pushRegistrationIsIgnoredForReviewAccount() throws Exception {
            OwnAccount own = ownAccount();
            String phoneToken = fcmToken();
            assertThat(registerPushToken(own.accessToken(), phoneToken).getResponse().getStatus()).isEqualTo(200);

            String judgeAt = read(reviewLoginOnSameDevice(own, CODE_1), "$.data.accessToken");
            assertThat(registerPushToken(judgeAt, phoneToken).getResponse().getStatus())
                    .as("앱 흐름을 막지 않는다").isEqualTo(200);

            assertThat(activePushOwner(phoneToken)).as("토큰 소유가 넘어가지 않는다").isEqualTo(own.id());
            assertThat(activePushTokens(JUDGE_1)).as("심사 계정에는 푸시 토큰이 없다").isZero();
        }

        @Test
        @DisplayName("심사 계정에 이미 활성 토큰이 있으면 등록 요청 때 내린다 — 시드 전에 남은 토큰으로 푸시가 가지 않는다")
        void existingReviewAccountTokenIsDeactivatedOnRegistration() throws Exception {
            String stale = fcmToken();
            jdbc.update("INSERT INTO DeviceToken (id, userId, token, platform, isActive, lastSeenAt) " +
                    "VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), ?, 'ANDROID', 1, NOW(6))", JUDGE_1.toString(), stale);
            String judgeAt = read(reviewLogin(CODE_1), "$.data.accessToken");

            assertThat(registerPushToken(judgeAt, fcmToken()).getResponse().getStatus()).isEqualTo(200);

            assertThat(activePushOwner(stale)).as("발송 경로가 읽는 활성 토큰이 남지 않는다").isNull();
            assertThat(activePushTokens(JUDGE_1)).isZero();
        }

        @Test
        @DisplayName("푸시 제외는 코드 계정만이다 — Play 심사 계정의 토큰 등록은 기존대로 저장된다")
        void playReviewerPushIsUnchanged() throws Exception {
            String playAt = tokenService.issueTokenPair(reload(PLAY_REVIEWER)).accessToken();
            String phoneToken = fcmToken();

            assertThat(registerPushToken(playAt, phoneToken).getResponse().getStatus()).isEqualTo(200);

            assertThat(activePushOwner(phoneToken)).isEqualTo(PLAY_REVIEWER);
        }

        @Test
        @DisplayName("기기에 본인 계정 토큰이 없어도 심사 계정에는 저장하지 않는다")
        void pushRegistrationIsIgnoredEvenForFreshToken() throws Exception {
            String judgeAt = read(reviewLogin(CODE_1), "$.data.accessToken");
            String phoneToken = fcmToken();

            assertThat(registerPushToken(judgeAt, phoneToken).getResponse().getStatus()).isEqualTo(200);

            assertThat(activePushOwner(phoneToken)).isNull();
            assertThat(activePushTokens(JUDGE_1)).isZero();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("실패")
    class Failure {

        @Test
        @DisplayName("목록에 없는 코드는 400 INVALID_REVIEW_CODE — 401 은 앱에서 토큰 재발급으로 이어지므로 쓰지 않는다")
        void unknownCodeIs400() throws Exception {
            expectError(reviewLogin(UNKNOWN_CODE), 400, "INVALID_REVIEW_CODE");
        }

        @Test
        @DisplayName("코드가 비었거나 없으면 400 INVALID_REVIEW_CODE")
        void blankCodeIs400() throws Exception {
            expectError(reviewLogin(""), 400, "INVALID_REVIEW_CODE");
            expectError(reviewLogin(new LinkedHashMap<>(), freshIp()), 400, "INVALID_REVIEW_CODE");
        }

        @Test
        @DisplayName("더미 멤버·일반 유저 계정에는 코드로 들어갈 수 없다 — 코드가 없다")
        void noCodeForOtherAccounts() throws Exception {
            expectError(reviewLogin("ruleup-demo-01"), 400, "INVALID_REVIEW_CODE");
            expectError(reviewLogin(DUMMY.toString()), 400, "INVALID_REVIEW_CODE");
        }

        @Test
        @DisplayName("정지된 심사 계정은 기존 계정 상태 게이트대로 403 ACCOUNT_BANNED")
        void bannedReviewAccountIsBlocked() throws Exception {
            if (!reload(JUDGE_4).isSuspended()) ban(JUDGE_4);

            expectError(reviewLogin(CODE_4), 403, "ACCOUNT_BANNED");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("탈퇴 — 코드로 다시 들어오면 복원된다")
    class Withdrawal {

        @Test
        @DisplayName("탈퇴한 심사 계정은 소셜 로그인과 같은 복원 경로로 들어온다 — restored=true, 같은 id")
        void restoresWithdrawnAccount() throws Exception {
            String at = read(reviewLoginFrom(CODE_3, "judge3-phone-" + uniq("")), "$.data.accessToken");
            MvcResult withdrawn = mvc.perform(delete("/api/v1/users/me")
                    .header("Authorization", "Bearer " + at)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(OM.writeValueAsString(Map.of("confirmPhrase", "탈퇴할게요")))).andReturn();
            assertThat(withdrawn.getResponse().getStatus()).isEqualTo(200);
            assertThat(reload(JUDGE_3).isWithdrawn()).isTrue();

            String phone = "judge3-phone-" + uniq("");
            MvcResult back = reviewLoginFrom(CODE_3, phone);

            assertThat(back.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(back, "$.data.isNewUser")).isFalse();
            assertThat((Boolean) read(back, "$.data.restored")).isTrue();
            assertThat((String) read(back, "$.data.user.id")).isEqualTo(JUDGE_3.toString());
            assertThat((String) read(back, "$.data.user.accountStatus")).isEqualTo("ACTIVE");
            User restored = reload(JUDGE_3);
            assertThat(restored.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(restored.getDeletedAt()).isNull();
            assertThat(restored.getInstallationId()).isEqualTo("review-inst-2029120101-03");
            assertThat(restored.getDeviceId()).as("복원도 기기 값을 받지 않는다").isNull();
            assertThat(me(read(back, "$.data.accessToken")).getResponse().getStatus()).isEqualTo(200);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("시도 제한 — IP 당 10분에 실패 10회")
    class AttemptLimit {

        @Test
        @DisplayName("실패 10회 뒤에는 맞는 코드도 429 TOO_MANY_ATTEMPTS 이고 Retry-After 초를 싣는다")
        void blocksAfterTenFailures() throws Exception {
            String ip = freshIp();
            for (int i = 0; i < 10; i++) {
                expectError(reviewLogin(codeBody(UNKNOWN_CODE), ip), 400, "INVALID_REVIEW_CODE");
            }

            MvcResult blocked = reviewLogin(codeBody(CODE_1), ip);

            expectError(blocked, 429, "TOO_MANY_ATTEMPTS");
            String retryAfter = blocked.getResponse().getHeader("Retry-After");
            assertThat(retryAfter).as("Retry-After 헤더").isNotBlank();
            int seconds = Integer.parseInt(retryAfter);
            assertThat(seconds).isPositive().isLessThanOrEqualTo(600);
            assertThat((Integer) read(blocked, "$.error.retryAfterSeconds")).isEqualTo(seconds);
        }

        @Test
        @DisplayName("다른 IP 는 영향이 없다")
        void otherIpUnaffected() throws Exception {
            String ip = freshIp();
            for (int i = 0; i < 10; i++) reviewLogin(codeBody(UNKNOWN_CODE), ip);

            assertThat(reviewLogin(CODE_1).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("X-Forwarded-For 앞쪽을 바꿔도 피할 수 없다 — ALB 가 붙인 마지막 값으로 센다")
        void spoofedPrefixDoesNotBypass() throws Exception {
            String albSeen = freshIp();
            for (int i = 0; i < 10; i++) {
                reviewLogin(codeBody(UNKNOWN_CODE), "10.9.9." + i + ", " + albSeen);
            }

            expectError(reviewLogin(codeBody(CODE_1), "10.8.8.8, " + albSeen), 429, "TOO_MANY_ATTEMPTS");
        }

        @Test
        @DisplayName("성공은 세지 않는다")
        void successesDoNotCount() throws Exception {
            String ip = freshIp();
            for (int i = 0; i < 12; i++) {
                assertThat(reviewLogin(codeBody(CODE_1), ip).getResponse().getStatus()).isEqualTo(200);
            }
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("코드 폐기 — 항목을 지우지 않고 revoked:true 로 바꾼다(유출 대응)")
    class RevokedCode {

        /** 폐기 전에 열린 세션 — 폐기 재배포 뒤에도 RT 가 남아 있는 상태. */
        private TokenService.TokenPair sessionOpenedBeforeRevocation() {
            return tokenService.issueTokenPair(reload(JUDGE_7));
        }

        @Test
        @DisplayName("폐기된 코드로는 로그인되지 않는다 — 목록에 없는 코드와 같은 400 INVALID_REVIEW_CODE")
        void revokedCodeCannotLogIn() throws Exception {
            expectError(reviewLogin(CODE_7), 400, "INVALID_REVIEW_CODE");
        }

        @Test
        @DisplayName("폐기 전에 열린 세션은 스위치가 켜져 있어도 재발급이 401 SESSION_EXPIRED — 종료 대상에서 빠지지 않는다")
        void sessionBeforeRevocationIsRefused() throws Exception {
            String rt = sessionOpenedBeforeRevocation().refreshToken();
            assertThat(liveInFamilyOf(rt)).isPositive();

            expectError(refresh(rt), 401, "SESSION_EXPIRED");

            assertThat(liveInFamilyOf(rt)).as("재발급 경로가 그 세션의 RT 를 회수해 커밋했다 — 스케줄러 없이").isZero();
        }

        @Test
        @DisplayName("스위치가 꺼진 뒤에도 끊긴다 — 폐기된 계정도 스위치 종료 대상이다")
        void revokedAccountIsAlsoCutBySwitchOff() throws Exception {
            String rt = sessionOpenedBeforeRevocation().refreshToken();
            passExpiry();

            expectError(refresh(rt), 401, "SESSION_EXPIRED");
            assertThat(liveInFamilyOf(rt)).as("스케줄러 없이 회수됐다").isZero();
        }

        @Test
        @DisplayName("종료 스케줄러는 스위치가 켜져 있어도 폐기된 코드 계정의 RT 를 회수한다 — 재발급 요청이 없어도 정리된다")
        void terminatorRevokesRevokedCodeSessionsWhileOn() throws Exception {
            sessionOpenedBeforeRevocation();
            String liveRt = read(reviewLogin(CODE_1), "$.data.refreshToken");
            int before = activeRefreshTokens(JUDGE_7);
            assertThat(before).isPositive();

            assertThat(terminator.revokeEndedSessions()).isPositive();

            assertThat(activeRefreshTokens(JUDGE_7)).isZero();
            assertThat(refresh(liveRt).getResponse().getStatus()).as("살아 있는 코드의 세션은 그대로").isEqualTo(200);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("심사 기능 종료 — 꺼진 시각 + AT 수명(30분) 안에 끊긴다")
    class Termination {

        @Test
        @DisplayName("expiresAt 이 지나면 맞는 코드도 404 — 재배포 없이 닫힌다")
        void loginIs404AfterExpiry() throws Exception {
            assertThat(reviewLogin(CODE_1).getResponse().getStatus()).isEqualTo(200);

            passExpiry();

            assertThat(reviewLogin(CODE_1).getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("꺼진 뒤에는 틀린 코드도 404 다 — 코드 대조까지 가지 않는다")
        void wrongCodeIsAlso404AfterExpiry() throws Exception {
            passExpiry();

            assertThat(reviewLogin(UNKNOWN_CODE).getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("꺼지면 다음 주기를 기다리지 않고 재발급이 바로 401 SESSION_EXPIRED — 새 AT 를 더 받을 수 없다")
        void refreshIsRefusedRightAfterSwitchOff() throws Exception {
            MvcResult login = reviewLogin(CODE_5);
            String rt = read(login, "$.data.refreshToken");

            passExpiry();   // 종료 스케줄러는 아직 돌지 않았다

            expectError(refresh(rt), 401, "SESSION_EXPIRED");
            assertThat(liveInFamilyOf(rt)).as("거절과 함께 그 세션의 RT 를 회수해 커밋했다").isZero();
            assertThat(me(read(login, "$.data.accessToken")).getResponse().getStatus())
                    .as("이미 받은 AT 는 남은 수명 동안 쓴다 — 그 만료가 끊김 보장의 끝이다").isEqualTo(200);
        }

        @Test
        @DisplayName("재발급 차단은 codes 계정에만 걸린다 — Play 심사 계정·일반 유저는 꺼진 뒤에도 재발급된다")
        void refreshBlockIsOnlyForCodeAccounts() throws Exception {
            String playRt = tokenService.issueTokenPair(reload(PLAY_REVIEWER)).refreshToken();
            String normalRt = read(signup(uniq("rv-refresh"), nickname("일반")), "$.data.refreshToken");

            passExpiry();

            assertThat(refresh(playRt).getResponse().getStatus()).isEqualTo(200);
            assertThat(refresh(normalRt).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("스위치가 켜져 있으면 종료 스케줄러는 살아 있는 코드 계정의 세션을 건드리지 않는다")
        void terminatorIdlesWhileOn() throws Exception {
            String rt = read(reviewLogin(CODE_5), "$.data.refreshToken");

            terminator.revokeEndedSessions();

            assertThat(refresh(rt).getResponse().getStatus()).isEqualTo(200);
            assertThat(reload(JUDGE_5).isWithdrawn()).as("켜져 있으면 탈퇴시키지 않는다").isFalse();
        }

        @Test
        @DisplayName("꺼지면 종료 스케줄러가 codes 계정을 탈퇴시켜 RT 를 모두 회수하고, 남은 AT 도 계정 상태 게이트가 곧바로 막는다")
        void terminatorRevokesCodeAccountSessions() throws Exception {
            MvcResult login = reviewLogin(CODE_5);
            String judgeAt = read(login, "$.data.accessToken");
            String judgeRt = read(login, "$.data.refreshToken");

            passExpiry();
            assertThat(me(judgeAt).getResponse().getStatus())
                    .as("스케줄러가 돌기 전에는 남은 AT 를 쓴다 — 30분은 상한이다").isEqualTo(200);

            assertThat(terminator.revokeEndedSessions()).as("처리한 것이 있다").isPositive();
            clock.reset();   // 다시 켜도 회수된 RT 는 살아나지 않는다

            assertThat(JUDGES).allSatisfy(j -> assertThat(activeRefreshTokens(j)).as("%s 의 살아 있는 RT", j).isZero());
            expectError(refresh(judgeRt), 401, "SESSION_EXPIRED");
            // 자동 탈퇴는 일반 탈퇴다 — 커밋되는 순간부터 기존 계정 상태 게이트가 WITHDRAWN 의 남은 AT 를 401 로 막는다.
            // 시계를 되돌려도 탈퇴 상태는 그대로다.
            expectError(me(judgeAt), 401, "LOGIN_REQUIRED");
        }

        @Test
        @DisplayName("종료 스케줄러는 Play 심사 계정과 일반 유저의 세션·푸시·계정을 건드리지 않는다 — 대상은 REVIEW_ACCOUNT_IDS 전체가 아니라 codes 뿐")
        void terminatorSparesOtherSessions() throws Exception {
            String playRt = tokenService.issueTokenPair(reload(PLAY_REVIEWER)).refreshToken();
            MvcResult normal = signup(uniq("rv-normal"), nickname("일반"));
            String normalRt = read(normal, "$.data.refreshToken");
            String normalPush = fcmToken();
            registerPushToken(read(normal, "$.data.accessToken"), normalPush);
            reviewLogin(CODE_5);

            passExpiry();
            terminator.revokeEndedSessions();
            clock.reset();

            assertThat(refresh(playRt).getResponse().getStatus()).isEqualTo(200);
            assertThat(refresh(normalRt).getResponse().getStatus()).isEqualTo(200);
            assertThat(activePushOwner(normalPush)).isEqualTo(UUID.fromString(read(normal, "$.data.user.id")));
            assertThat(reload(PLAY_REVIEWER).isWithdrawn()).as("Play 심사 계정은 탈퇴시키지 않는다").isFalse();
            assertThat(reload(UUID.fromString(read(normal, "$.data.user.id"))).isWithdrawn()).isFalse();
        }

        @Test
        @DisplayName("멱등하다 — 다시 돌면 회수할 것이 없다(여러 태스크가 동시에 돌아도 된다)")
        void terminatorIsIdempotent() throws Exception {
            reviewLogin(CODE_5);
            passExpiry();

            assertThat(terminator.revokeEndedSessions()).isPositive();
            assertThat(terminator.revokeEndedSessions()).isZero();
        }

        @Test
        @DisplayName("회수된 RT 는 스위치를 다시 켜도 살아나지 않는다 — 코드를 다시 넣어야 한다")
        void revokedSessionsStayRevoked() throws Exception {
            String rt = read(reviewLogin(CODE_5), "$.data.refreshToken");
            passExpiry();
            terminator.revokeEndedSessions();

            clock.reset();   // 다시 켜짐

            expectError(refresh(rt), 401, "SESSION_EXPIRED");
            assertThat(reviewLogin(CODE_5).getResponse().getStatus()).isEqualTo(200);
        }
    }
}
