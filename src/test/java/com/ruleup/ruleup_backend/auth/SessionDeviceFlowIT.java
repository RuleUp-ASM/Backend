package com.ruleup.ruleup_backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.auth.domain.SocialToken;
import com.ruleup.ruleup_backend.auth.SocialTokenRepository;
import com.ruleup.ruleup_backend.notification.NotificationRepository;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
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
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 로그인 분기·단일 활성 기기·RT 회전/재사용 감지 스펙 테스트 (테크 스펙 4-3, 회원 정책 §7·§8).
 *
 * 커버 범위
 *  1) 계정 상태 분기: BANNED 로그인 403(재로그인=사실상 재가입 경로도 차단), LOCKED 는 열람 전용 로그인 허용
 *  2) 단일 활성 기기: 다른 기기 로그인 시 기존 RT 전부 revoke + 기기정보 교체 + 세션 종료 알림
 *  3) 설치 인계: 기존 회원이 타 계정 점유 설치에서 로그인하면 이전 계정 연결 해제(+세션 종료)
 *  4) RT 회전: refresh 마다 새 페어 + 기존 즉시 revoke (같은 family 유지)
 *  5) RT 재사용 감지: revoke 된 RT 재제출 → family 전체 revoke (탈취 대응) — 새 RT도 무효
 *  6) 로그아웃: RT revoke + 멱등, 이후 refresh 401
 *  7) social_tokens: 로그인 시 IdP 토큰 암호화 저장(unlink 근거)
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SessionDeviceFlowIT {

    // ===== 제재 (테스트 보조) =====
    // 정지의 종류·기간은 sanctions 가 소유하고 users.status 전이는 그와 같은 트랜잭션이어야 한다.
    // 상태값만 직접 바꾸면 게이트가 스스로 되돌려 버리므로 운영 경로와 같은 서비스를 거친다.
    @Autowired com.ruleup.ruleup_backend.sanction.SanctionService sanctionService;

    private void lock(java.util.UUID userId) {
        sanctionService.impose(userId,
                com.ruleup.ruleup_backend.sanction.domain.SanctionTrack.DISCRETIONARY,
                com.ruleup.ruleup_backend.sanction.domain.SanctionType.LOCK, null,
                com.ruleup.ruleup_backend.sanction.domain.SanctionReason.REPORT_CONFIRMED,
                "테스트 잠금",
                com.ruleup.ruleup_backend.sanction.domain.SanctionSource.DIRECT, null, null,
                java.time.Instant.now().plus(java.time.Duration.ofDays(30)));
    }

    private void ban(java.util.UUID userId) {
        sanctionService.impose(userId,
                com.ruleup.ruleup_backend.sanction.domain.SanctionTrack.DISCRETIONARY,
                com.ruleup.ruleup_backend.sanction.domain.SanctionType.BAN, null,
                com.ruleup.ruleup_backend.sanction.domain.SanctionReason.ILLEGAL_CONTENT,
                "테스트 영구 정지",
                com.ruleup.ruleup_backend.sanction.domain.SanctionSource.DIRECT, null, null, null);
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired WebApplicationContext wac;
    private final ObjectMapper om = new ObjectMapper();
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired SocialTokenRepository socialTokenRepository;
    @Autowired RefreshTokenCleanupService refreshTokenCleanupService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    // ==================================================================
    // 헬퍼
    // ==================================================================

    private static String uniq() {
        return "sd" + System.nanoTime() + "n" + SEQ.incrementAndGet();
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

    /** 가입까지 완료하고 (userId 조회용 tag, refreshToken) 을 돌려준다. */
    private MvcResult signup(String tag) throws Exception {
        MvcResult login = postJson("/api/v1/auth/oauth/kakao", loginBody(tag, "inst-" + tag, "dev-" + tag));
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        String token = read(login, "$.data.signupToken");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signupToken", token);
        body.put("installationId", "inst-" + tag);
        body.put("nickname", "세션유저" + SEQ.get());
        body.put("interestCategories", List.of("EXERCISE"));
        body.put("birthDate", "2000-05-27");
        body.put("gender", "MALE");
        Map<String, Object> ag = new LinkedHashMap<>();
        ag.put("termsOfService", agreement(true));
        ag.put("privacyPolicy", agreement(true));
        ag.put("locationService", agreement(true));
        ag.put("marketing", agreement(false));
        ag.put("event", agreement(false));
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

    private MvcResult refresh(String refreshToken) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refreshToken", refreshToken);
        return postJson("/api/v1/auth/refresh", m);
    }

    private MvcResult logout(String accessToken, String refreshToken) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refreshToken", refreshToken);
        return mvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(m))).andReturn();
    }

    private UUID insertRefreshToken(UUID userId, Instant expiresAt,
                                    Instant revokedAt, Instant reuseDetectedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO refresh_tokens
                            (id, user_id, family_id, token_hash, expires_at, revoked_at, reuse_detected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                bytes(id), bytes(userId), bytes(UUID.randomUUID()),
                TokenService.sha256(UUID.randomUUID().toString()), Timestamp.from(expiresAt),
                revokedAt != null ? Timestamp.from(revokedAt) : null,
                reuseDetectedAt != null ? Timestamp.from(reuseDetectedAt) : null);
        return id;
    }

    private int refreshTokenCount(UUID tokenId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE id = ?", Integer.class, bytes(tokenId));
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private void expectError(MvcResult res, int status, String code) throws Exception {
        assertThat(res.getResponse().getStatus()).as("HTTP status").isEqualTo(status);
        assertThat((String) read(res, "$.error.code")).isEqualTo(code);
    }

    // ==================================================================
    // 1) 계정 상태 분기
    // ==================================================================

    @Nested
    @DisplayName("계정 상태 분기")
    class AccountStatusBranching {

        @Test
        @DisplayName("영구 정지(BANNED) 계정은 로그인 자체가 403 ACCOUNT_BANNED — 재가입 경로도 없다")
        void banned_login_rejected() throws Exception {
            String tag = uniq();
            signup(tag);
            ban(findUser(tag).getId());

            // 로그인 차단 — (provider, subject)가 살아 있으므로 신규 가입 분기로도 못 빠진다
            MvcResult res = postJson("/api/v1/auth/oauth/kakao", loginBody(tag, "inst-" + tag, "dev-" + tag));
            expectError(res, 403, "ACCOUNT_BANNED");
        }

        @Test
        @DisplayName("잠금 계정은 열람 전용으로 로그인된다 — accountStatus=SUSPENDED + lockInfo")
        void locked_login_allowed_readonly() throws Exception {
            String tag = uniq();
            signup(tag);
            lock(findUser(tag).getId());

            MvcResult res = postJson("/api/v1/auth/oauth/kakao", loginBody(tag, "inst-" + tag, "dev-" + tag));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.isNewUser")).isFalse();
            assertThat((String) read(res, "$.data.user.accountStatus")).isEqualTo("SUSPENDED");
            assertThat((Object) read(res, "$.data.user.lockInfo")).isNotNull();
            assertThat((String) read(res, "$.data.accessToken")).isNotBlank();   // 열람 전용 홈 진입용
        }

        @Test
        @DisplayName("잠금 계정도 조회는 된다 — 마이페이지·본인 기록 열람 허용(§7.2)")
        void locked_can_read() throws Exception {
            String tag = uniq();
            String at = lockAndRelogin(tag);

            assertThat(mvc.perform(get("/api/v1/users/me")
                    .header("Authorization", "Bearer " + at)).andReturn()
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(mvc.perform(get("/api/v1/profile")
                    .header("Authorization", "Bearer " + at)).andReturn()
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("잠금 계정의 쓰기(프로필 수정·챌린지 생성)는 403 ACCOUNT_LOCKED 로 막힌다")
        void locked_writes_blocked() throws Exception {
            String tag = uniq();
            String at = lockAndRelogin(tag);

            // 프로필 편집 — 정책상 명시적 차단 대상
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("nickname", "잠금변경" + SEQ.get());
            MvcResult patch = mvc.perform(patch("/api/v1/profile")
                    .header("Authorization", "Bearer " + at)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(profile))).andReturn();
            expectError(patch, 403, "ACCOUNT_LOCKED");

            // 챌린지 생성 — 참여·생성 전면 차단
            MvcResult create = mvc.perform(post("/api/v1/challenges")
                    .header("Authorization", "Bearer " + at)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andReturn();
            expectError(create, 403, "ACCOUNT_LOCKED");
        }

        @Test
        @DisplayName("잠금 중에도 로그아웃은 허용된다 — 세션을 못 끊으면 로그인 상태에 갇힌다")
        void locked_can_logout() throws Exception {
            String tag = uniq();
            signup(tag);
            lock(findUser(tag).getId());

            MvcResult login = postJson("/api/v1/auth/oauth/kakao", loginBody(tag, "inst-" + tag, "dev-" + tag));
            String at = read(login, "$.data.accessToken");
            String rt = read(login, "$.data.refreshToken");

            assertThat(logout(at, rt).getResponse().getStatus()).isEqualTo(200);
            expectError(refresh(rt), 401, "SESSION_EXPIRED");   // 실제로 세션이 끊겼다
        }

        @Test
        @DisplayName("잠금 중에도 탈퇴는 허용된다 (§7.5)")
        void locked_can_withdraw() throws Exception {
            String tag = uniq();
            String at = lockAndRelogin(tag);

            Map<String, Object> withdrawBody = new LinkedHashMap<>();
            withdrawBody.put("confirmPhrase", "탈퇴할게요");
            MvcResult withdrawn = mvc.perform(delete("/api/v1/users/me")
                    .header("Authorization", "Bearer " + at)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(withdrawBody))).andReturn();
            assertThat(withdrawn.getResponse().getStatus()).isEqualTo(200);
            assertThat(findUser(tag).isWithdrawn()).isTrue();
        }

        /** 가입 → 잠금 → 재로그인해서 잠금 상태의 accessToken 을 얻는다. */
        private String lockAndRelogin(String tag) throws Exception {
            signup(tag);
            lock(findUser(tag).getId());

            MvcResult res = postJson("/api/v1/auth/oauth/kakao", loginBody(tag, "inst-" + tag, "dev-" + tag));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            return read(res, "$.data.accessToken");
        }
    }

    // ==================================================================
    // 2) 단일 활성 기기
    // ==================================================================

    @Nested
    @DisplayName("단일 활성 기기")
    class SingleActiveDevice {

        @Test
        @DisplayName("다른 기기 로그인 시: 기존 RT 전부 revoke + 기기정보 교체 + 세션 종료 알림")
        void new_device_login_revokes_old_sessions() throws Exception {
            String tag = uniq();
            MvcResult signup = signup(tag);
            String oldRefresh = read(signup, "$.data.refreshToken");

            // 다른 기기에서 로그인 (deviceId·installationId 모두 새 값 — 기기 교체 시나리오)
            MvcResult relogin = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(tag, "inst-" + tag + "-b", "dev-" + tag + "-b"));
            assertThat(relogin.getResponse().getStatus()).isEqualTo(200);

            // 기존 기기의 RT 는 전부 무효 → 401 SESSION_EXPIRED
            expectError(refresh(oldRefresh), 401, "SESSION_EXPIRED");

            // 활성 기기 정보는 새 기기로 교체
            User user = findUser(tag);
            assertThat(user.getDeviceId()).isEqualTo("dev-" + tag + "-b");
            assertThat(user.getInstallationId()).isEqualTo("inst-" + tag + "-b");

            // 기존 기기에 "다른 기기에서 로그인됨" 알림 — 계정 보안 고지라 필수(A)다.
            boolean notified = notificationRepository
                    .findInbox(user.getId(), null, null, org.springframework.data.domain.Limit.unlimited())
                    .stream()
                    .anyMatch(n -> NotificationType.DEVICE_LOGGED_OUT.name().equals(n.getType()));
            assertThat(notified).isTrue();
        }

        @Test
        @DisplayName("같은 기기 재로그인은 세션을 끊지 않는다")
        void same_device_relogin_keeps_sessions() throws Exception {
            String tag = uniq();
            MvcResult signup = signup(tag);
            String oldRefresh = read(signup, "$.data.refreshToken");

            MvcResult relogin = postJson("/api/v1/auth/oauth/kakao", loginBody(tag, "inst-" + tag, "dev-" + tag));
            assertThat(relogin.getResponse().getStatus()).isEqualTo(200);

            // 같은 기기였으므로 기존 RT 는 여전히 유효 (회전 성공 = 200)
            assertThat(refresh(oldRefresh).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("타 계정이 점유한 설치에서 기존 회원이 로그인하면 설치를 인계받고 이전 계정 세션은 종료된다")
        void installation_takeover_between_accounts() throws Exception {
            String tagA = uniq();
            MvcResult signupA = signup(tagA);
            String refreshA = read(signupA, "$.data.refreshToken");

            String tagB = uniq();
            signup(tagB);

            // B가 A의 설치(inst-tagA)에서 로그인 — 기존 회원 로그인은 차단 대상이 아니라 인계 대상
            MvcResult loginB = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(tagB, "inst-" + tagA, "dev-" + tagA));
            assertThat(loginB.getResponse().getStatus()).isEqualTo(200);

            // 설치는 B에게 인계, A의 연결은 해제 (uq_users_installation_id)
            assertThat(findUser(tagB).getInstallationId()).isEqualTo("inst-" + tagA);
            assertThat(findUser(tagA).getInstallationId()).isNull();

            // A의 세션은 전부 종료
            expectError(refresh(refreshA), 401, "SESSION_EXPIRED");
        }
    }

    // ==================================================================
    // 3) RT 회전 · 재사용 감지 · 로그아웃
    // ==================================================================

    @Nested
    @DisplayName("토큰 회전")
    class TokenRotation {

        @Test
        @DisplayName("refresh 는 회전: 새 페어 발급 + 기존 RT 즉시 무효")
        void refresh_rotates_and_revokes_old() throws Exception {
            String tag = uniq();
            String rt1 = read(signup(tag), "$.data.refreshToken");

            MvcResult r = refresh(rt1);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            String rt2 = read(r, "$.data.refreshToken");
            assertThat(rt2).isNotEqualTo(rt1);

            // 회전된 새 RT 는 정상 사용 가능
            assertThat(refresh(rt2).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("같은 RT 동시 제출은 하나만 회전 성공하고 후발 요청은 재사용으로 감지한다")
        void concurrent_refresh_has_single_winner() throws Exception {
            String tag = uniq();
            String rt1 = read(signup(tag), "$.data.refreshToken");

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                java.util.concurrent.Callable<MvcResult> call = () -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 refresh 시작 대기 시간 초과");
                    }
                    return refresh(rt1);
                };

                Future<MvcResult> first = executor.submit(call);
                Future<MvcResult> second = executor.submit(call);
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                MvcResult r1 = first.get(10, TimeUnit.SECONDS);
                MvcResult r2 = second.get(10, TimeUnit.SECONDS);
                assertThat(List.of(r1.getResponse().getStatus(), r2.getResponse().getStatus()))
                        .containsExactlyInAnyOrder(200, 401);

                MvcResult winner = r1.getResponse().getStatus() == 200 ? r1 : r2;
                MvcResult rejected = r1.getResponse().getStatus() == 401 ? r1 : r2;
                expectError(rejected, 401, "SESSION_EXPIRED");

                // 후발 제출은 기존 정책대로 탈취 의심 재사용이다. 따라서 선발 요청이 만든
                // 자식 RT까지 같은 family 전체가 폐기되어 다시 로그인해야 한다.
                String rotated = read(winner, "$.data.refreshToken");
                expectError(refresh(rotated), 401, "SESSION_EXPIRED");
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("revoke 된 RT 재제출(재사용 감지) 시 family 전체를 무효화한다 — 탈취 대응")
        void reuse_detection_revokes_whole_family() throws Exception {
            String tag = uniq();
            String rt1 = read(signup(tag), "$.data.refreshToken");

            String rt2 = read(refresh(rt1), "$.data.refreshToken");   // rt1 은 이 시점에 revoke

            // 탈취범이 rt1 을 재사용 → 401 + family 전체 revoke
            expectError(refresh(rt1), 401, "SESSION_EXPIRED");

            // 정상 사용자가 갖고 있던 rt2 도 함께 무효 (재로그인 요구)
            expectError(refresh(rt2), 401, "SESSION_EXPIRED");
        }

        @Test
        @DisplayName("로그아웃하면 해당 RT 는 무효가 되고, 로그아웃은 멱등이다")
        void logout_revokes_and_is_idempotent() throws Exception {
            String tag = uniq();
            MvcResult signup = signup(tag);
            String at = read(signup, "$.data.accessToken");
            String rt = read(signup, "$.data.refreshToken");

            assertThat(logout(at, rt).getResponse().getStatus()).isEqualTo(200);
            assertThat(logout(at, rt).getResponse().getStatus()).isEqualTo(200);   // 멱등
            expectError(refresh(rt), 401, "SESSION_EXPIRED");
        }
    }

    // ==================================================================
    // 4) RT 보관기간 정리
    // ==================================================================

    @Nested
    @DisplayName("토큰 보관기간 정리")
    class TokenRetentionCleanup {

        @Test
        @DisplayName("일반 만료·폐기는 30일, 재사용 탐지 기록은 180일 보관한다")
        void cleanup_respects_ordinary_and_reuse_retention() throws Exception {
            String tag = uniq();
            UUID userId = UUID.fromString(read(signup(tag), "$.data.user.id"));
            Instant now = Instant.now();

            UUID expired31d = insertRefreshToken(userId, now.minus(31, ChronoUnit.DAYS), null, null);
            UUID revoked31d = insertRefreshToken(userId, now.plus(7, ChronoUnit.DAYS),
                    now.minus(31, ChronoUnit.DAYS), null);
            UUID expired29d = insertRefreshToken(userId, now.minus(29, ChronoUnit.DAYS), null, null);
            UUID reused31d = insertRefreshToken(userId, now.minus(200, ChronoUnit.DAYS),
                    now.minus(31, ChronoUnit.DAYS), now.minus(31, ChronoUnit.DAYS));
            UUID reused181d = insertRefreshToken(userId, now.minus(200, ChronoUnit.DAYS),
                    now.minus(181, ChronoUnit.DAYS), now.minus(181, ChronoUnit.DAYS));

            refreshTokenCleanupService.cleanupOldTokens();

            assertThat(refreshTokenCount(expired31d)).isZero();
            assertThat(refreshTokenCount(revoked31d)).isZero();
            assertThat(refreshTokenCount(reused181d)).isZero();
            assertThat(refreshTokenCount(expired29d)).isEqualTo(1);
            assertThat(refreshTokenCount(reused31d)).isEqualTo(1);
        }
    }

    // ==================================================================
    // 5) social_tokens 저장
    // ==================================================================

    @Nested
    @DisplayName("소셜 토큰")
    class SocialTokenStorage {          // 도메인 엔티티 SocialToken 과 이름이 겹치면 안이 가려진다

        @Test
        @DisplayName("가입·로그인 시 IdP 토큰이 암호화되어 social_tokens 에 저장된다 (unlink 근거)")
        void social_tokens_stored_encrypted() throws Exception {
            String tag = uniq();
            signup(tag);
            User user = findUser(tag);

            SocialToken stored = socialTokenRepository
                    .findById(new SocialToken.Key(user.getId(), OAuthProvider.KAKAO))
                    .orElseThrow();
            assertThat(stored.getAccessTokenEnc()).isNotEmpty();
            assertThat(stored.getEncryptionKeyVersion()).isPositive();
            // 원문이 그대로 저장되면 안 된다 (Mock IdP 토큰: "mock-idp-at-" + code)
            assertThat(new String(stored.getAccessTokenEnc(), StandardCharsets.UTF_8))
                    .doesNotContain("mock-idp-at-");
        }
    }
}
