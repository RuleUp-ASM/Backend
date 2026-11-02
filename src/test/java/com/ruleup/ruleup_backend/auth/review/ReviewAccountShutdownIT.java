package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.TestcontainersConfiguration.MutableClock;
import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.score.service.ScoreService;
import com.ruleup.ruleup_backend.user.UserAccountService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.UserStatus;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ruleup.ruleup_backend.auth.review.ReviewAccountFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 심사 기능이 꺼지면 심사 계정을 <b>자동 탈퇴</b>시킨다 (심사 계정 테크 스펙 5-1 · 5-5 · 5-8 · 5-10).
 *
 * <p>심사 계정은 일반 유저와 같은 계정이다 — 방 격리가 없어 실제 유저 방에 들어가고, 실제 유저가 심사위원 방에
 * 들어온다. 그래서 평가가 끝나면 종료 스케줄러가 codes 계정을 <b>일반 탈퇴와 같은 흐름</b>
 * ({@link UserAccountService#withdraw})으로 탈퇴시킨다. 참여 중인 방에서 나가고, 방장이었던 방은 다른 멤버가 있으면
 * 봇 방장으로 넘어가며, 혼자였던 방은 닫힌다.
 *
 * <h4>회차 — 계정은 다음 심사위원에게 넘기지 않는다</h4>
 * 일반 탈퇴는 소프트 탈퇴라 동의 상태·문의 등 그 사람의 데이터가 계정에 남는다. 같은 계정을 다음 심사위원에게 주면
 * 그대로 이어받는다. 그래서 <b>회차마다 새 계정</b>을 시드하고, 코드 로그인은 계정의 바뀌지 않는 subject 에 적힌 회차가
 * 설정의 {@code round} 와 같을 때만 받는다 — 지난 회차 계정은 ACTIVE 로 남아 있든, 종료가 늦어 새 회차에 탈퇴됐든 코드가
 * 맞아도 들어오지 못한다(JUDGE_8). 같은 회차 안에서의 탈퇴(직접 해 본 탈퇴, 잠깐 껐다 켠 경우)는 복원된다.
 * 새 UUID·새 설정으로 바꾸는 다음 회차 흐름은 설정이 다른 컨텍스트가 필요해 ReviewNextRoundIT 에 둔다.
 *
 * <h4>여러 태스크·부분 실패</h4>
 * 스케줄러는 ShedLock 없이 태스크마다 돈다. 계정 행을 잠그고 상태를 다시 본 뒤 같은 트랜잭션에서 탈퇴시키므로
 * 겹쳐 돌아도 계정마다 탈퇴는 한 번이다 — 테스트는 한 실행이 계정을 쥔 것을 확인한 뒤 다른 실행을 출발시키고, 두 번째가
 * 실제로 그 행 잠금에서 기다렸는지 DB 로 확인한다. 한 계정이 탈퇴 도중 실패하면 그 계정의 쓰기(상태·RT·방)는 모두
 * 롤백되고 나머지는 계속하며, 실패한 계정은 다음 실행에서 다시 한다.
 *
 * <p>스위치는 시계를 expiresAt 뒤로 넘겨 끈다. 탈퇴는 스위트 전체의 심사 계정 행에 남으므로
 * 끝나면 {@link ReviewAccountFixture#reviveAll()} 로 되살린다(다른 테스트도 시작할 때 부른다).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReviewAccountShutdownIT extends ChallengeApiSupport {

    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserRepository userRepository;
    @Autowired ScoreService scoreService;
    @Autowired AgreementService agreementService;
    @Autowired AppProperties props;
    @Autowired TransactionTemplate tx;
    @Autowired MutableClock clock;
    @Autowired ReviewSessionTerminator terminator;
    /** 실제 탈퇴를 그대로 타되, 동시 실행·부분 실패를 만들 때만 끼어든다. */
    @MockitoSpyBean UserAccountService userAccountService;
    /** 탈퇴 흐름의 마지막 단계(방 나가기) — 앞 단계의 쓰기가 끝난 뒤 실패를 넣는 자리. */
    @MockitoSpyBean ChallengeMemberService challengeMemberService;

    MockMvc mvc;
    ReviewAccountFixture fixture;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        fixture = new ReviewAccountFixture(userRepository, scoreService, agreementService, props, tx, jdbcTemplate);
        fixture.ensureAll();
        fixture.reviveAll();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        fixture.reviveAll();
    }

    // ===== 헬퍼 =====

    private void switchOff() {
        clock.advance(Duration.between(clock.instant(), EXPIRES_AT).plusMinutes(1));
    }

    private void switchOffAndRunTerminator() {
        switchOff();
        terminator.revokeEndedSessions();
    }

    private UserStatus statusOf(UUID userId) {
        return userRepository.findById(userId).orElseThrow().getStatus();
    }

    private UUID room(UUID owner, List<UUID> members) {
        UUID id = insertChallenge(owner, "CAREER_PRODUCTIVITY", "ACTIVE", "GROUP");
        insertActiveMembership(id, owner, "OWNER");
        for (UUID m : members) insertActiveMembership(id, m, "MEMBER");
        jdbcTemplate.update("UPDATE challenges SET participant_count = ? WHERE id = ?", members.size() + 1, bytes(id));
        return id;
    }

    private Map<String, Object> roomRow(UUID challengeId) {
        return jdbcTemplate.queryForMap(
                "SELECT owner_type, owner_id, deleted_at FROM challenges WHERE id = ?", (Object) bytes(challengeId));
    }

    private String memberStatus(UUID challengeId, UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM challenge_members WHERE challenge_id = ? AND user_id = ?",
                String.class, bytes(challengeId), bytes(userId));
    }

    private int liveRefreshTokens(UUID userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, bytes(userId));
        return n == null ? 0 : n;
    }

    private void reviewLoginFor(String code) throws Exception {
        assertThat(codeLogin(code).getResponse().getStatus()).isEqualTo(200);
    }

    private MvcResult codeLogin(String code) throws Exception {
        int n = IP_SEQ.incrementAndGet();
        return mvc.perform(post("/api/v1/auth/review-login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "198.21." + (n / 250) + "." + (n % 250 + 1))
                .content(OM.writeValueAsString(Map.of("code", code)))).andReturn();
    }

    /**
     * 잠금 대기 조회 전용 루트 연결. 앱 연결(Testcontainers 기본 test 계정)에는 잠금 정보를 볼 권한이 없다 —
     * {@code performance_schema} 의 잠금 테이블은 앱 스키마 밖이다. 공유 컨테이너의 루트로 따로 붙는다(RestrictedAdminDb 와 같은 방식).
     */
    private static final JdbcTemplate LOCK_INSPECTOR = new JdbcTemplate(new DriverManagerDataSource(
            TestcontainersConfiguration.sharedMysql().getJdbcUrl(), "root",
            TestcontainersConfiguration.sharedMysql().getPassword()));

    /**
     * <b>{@code users} 테이블의 그 계정 행</b>에서 다른 트랜잭션이 기다리고 있는지 본다 — 경합이 실제로 일어났다는 증거.
     * DB 전체의 아무 대기가 아니라 대기 중인 잠금의 테이블과 행(PK 16바이트의 hex)까지 맞춘다.
     */
    private boolean awaitRowLockWait(UUID userId, Duration timeout) throws InterruptedException {
        String pkHex = userId.toString().replace("-", "").toUpperCase();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = LOCK_INSPECTOR.queryForObject("""
                    SELECT COUNT(*)
                      FROM performance_schema.data_lock_waits w
                      JOIN performance_schema.data_locks req ON req.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                      JOIN performance_schema.data_locks blk ON blk.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID
                     WHERE req.OBJECT_NAME = 'users' AND req.LOCK_TYPE = 'RECORD'
                       AND UPPER(req.LOCK_DATA) LIKE CONCAT('%', ?, '%')
                       AND blk.OBJECT_NAME = 'users' AND UPPER(blk.LOCK_DATA) LIKE CONCAT('%', ?, '%')""",
                    Integer.class, pkHex, pkHex);
            if (waiting != null && waiting > 0) return true;
            Thread.sleep(20);
        }
        return false;
    }

    // =====================================================================
    @Nested
    @DisplayName("자동 탈퇴 — 일반 탈퇴와 같은 흐름")
    class Withdrawal {

        @Test
        @DisplayName("꺼지면 codes 계정을 모두 탈퇴시킨다 — 폐기된 코드 계정 포함")
        void withdrawsAllCodeAccounts() {
            switchOffAndRunTerminator();

            assertThat(JUDGES).allSatisfy(j -> assertThat(statusOf(j)).as("%s", j).isEqualTo(UserStatus.WITHDRAWN));
            assertThat(JUDGES).allSatisfy(j -> assertThat(liveRefreshTokens(j)).as("%s 의 살아 있는 RT", j).isZero());
        }

        @Test
        @DisplayName("codes 에 없는 계정은 탈퇴시키지 않는다 — Play 심사 계정·더미 멤버·일반 유저")
        void sparesOtherAccounts() throws Exception {
            UUID normal = member(uniq("shut-normal")).id();

            switchOffAndRunTerminator();

            assertThat(statusOf(PLAY_REVIEWER)).isNotEqualTo(UserStatus.WITHDRAWN);
            assertThat(statusOf(DUMMY)).isNotEqualTo(UserStatus.WITHDRAWN);
            assertThat(statusOf(normal)).isNotEqualTo(UserStatus.WITHDRAWN);
        }

        @Test
        @DisplayName("켜져 있는 동안에는 탈퇴시키지 않는다")
        void doesNothingWhileOn() {
            terminator.revokeEndedSessions();

            assertThat(statusOf(JUDGE_5)).isNotEqualTo(UserStatus.WITHDRAWN);
        }

        @Test
        @DisplayName("방장이던 방에 다른 멤버가 있으면 일반 탈퇴처럼 봇 방장으로 넘어간다 — 남은 실제 유저의 방은 계속된다")
        void ownedRoomWithOthersGoesToBotOwner() throws Exception {
            UUID realUser = member(uniq("shut-stay")).id();
            UUID roomId = room(JUDGE_5, List.of(realUser));

            switchOffAndRunTerminator();

            Map<String, Object> row = roomRow(roomId);
            assertThat(row.get("owner_type")).isEqualTo("BOT");
            assertThat(row.get("owner_id")).isNull();
            assertThat(row.get("deleted_at")).as("방은 닫히지 않는다").isNull();
            assertThat(memberStatus(roomId, JUDGE_5)).isNotEqualTo("ACTIVE");
            assertThat(memberStatus(roomId, realUser)).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("혼자 남은 방은 일반 탈퇴처럼 닫힌다")
        void soloRoomIsClosed() {
            UUID roomId = room(JUDGE_5, List.of());

            switchOffAndRunTerminator();

            assertThat(roomRow(roomId).get("deleted_at")).isNotNull();
        }

        @Test
        @DisplayName("실제 유저가 방장인 방에 참여 중이었으면 그 방에서만 나간다 — 방장은 그대로")
        void memberOfRealUsersRoomJustLeaves() throws Exception {
            UUID realOwner = member(uniq("shut-owner")).id();
            UUID roomId = room(realOwner, List.of(JUDGE_5));

            switchOffAndRunTerminator();

            assertThat(memberStatus(roomId, JUDGE_5)).isNotEqualTo("ACTIVE");
            assertThat(memberStatus(roomId, realOwner)).isEqualTo("ACTIVE");
            assertThat(roomRow(roomId).get("owner_type")).isEqualTo("USER");
        }

        @Test
        @DisplayName("멱등하다 — 이미 탈퇴한 계정은 다시 처리하지 않는다")
        void idempotent() {
            switchOffAndRunTerminator();

            assertThat(terminator.revokeEndedSessions()).isZero();
            assertThat(statusOf(JUDGE_5)).isEqualTo(UserStatus.WITHDRAWN);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("여러 태스크·부분 실패")
    class Concurrency {

        @Test
        @DisplayName("두 태스크가 같은 계정을 동시에 잡아도 탈퇴는 한 번이다 — 두 번째는 첫 번째가 쥔 행 잠금에서 기다린다")
        void concurrentRunsWithdrawEachAccountOnce() throws Exception {
            UUID realUser = member(uniq("shut-race")).id();
            UUID roomId = room(JUDGE_5, List.of(realUser));
            Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();
            CountDownLatch firstHoldsJudge5 = new CountDownLatch(1);
            AtomicBoolean secondWaitedOnLock = new AtomicBoolean();
            doAnswer(inv -> {
                UUID id = inv.getArgument(0);
                int n = calls.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                if (JUDGE_5.equals(id) && n == 1) {
                    // 첫 실행이 JUDGE_5 행을 잠근 채 탈퇴에 들어왔다. 두 번째 실행이 같은 행에서 기다릴 때까지 쥐고 있는다.
                    firstHoldsJudge5.countDown();
                    secondWaitedOnLock.set(awaitRowLockWait(JUDGE_5, Duration.ofSeconds(10)));
                }
                return inv.callRealMethod();
            }).when(userAccountService).withdraw(any(), anyString());
            switchOff();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> first = pool.submit(() -> terminator.revokeEndedSessions());
                assertThat(firstHoldsJudge5.await(30, TimeUnit.SECONDS)).as("첫 실행이 JUDGE_5 처리에 들어갔다").isTrue();
                Future<Integer> second = pool.submit(() -> terminator.revokeEndedSessions());
                for (Future<Integer> run : List.of(first, second)) {
                    assertThatCode(() -> run.get(60, TimeUnit.SECONDS)).as("겹쳐 돈 실행이 실패하지 않는다").doesNotThrowAnyException();
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(secondWaitedOnLock).as("두 번째 실행이 JUDGE_5 행 잠금에서 실제로 기다렸다 — 잠금이 없으면 기다리지 않고 한 번 더 탈퇴시킨다")
                    .isTrue();
            assertThat(JUDGES).allSatisfy(j -> {
                assertThat(statusOf(j)).as("%s", j).isEqualTo(UserStatus.WITHDRAWN);
                assertThat(calls.getOrDefault(j, new AtomicInteger()).get()).as("%s 의 탈퇴 호출 수", j).isEqualTo(1);
            });
            assertThat(roomRow(roomId).get("owner_type")).isEqualTo("BOT");
            assertThat(memberStatus(roomId, realUser)).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("한 계정이 탈퇴 도중 실패하면 그 계정의 쓰기는 모두 롤백되고, 나머지는 탈퇴되며, 다음 실행에서 다시 한다")
        void failedAccountIsRolledBackAndRetried() throws Exception {
            // JUDGE_2 — 살아 있는 세션 하나, 실제 유저와 함께 쓰는 방의 방장
            reviewLoginFor(CODE_2);
            UUID realUser = member(uniq("shut-partial")).id();
            UUID roomId = room(JUDGE_2, List.of(realUser));
            int liveBefore = liveRefreshTokens(JUDGE_2);
            assertThat(liveBefore).isPositive();

            // 탈퇴의 마지막 단계에서 실패시킨다 — 그때는 이미 상태 변경·RT 회수·방 나가기(봇 방장 전환)가 쓰인 뒤다
            doAnswer(inv -> {
                inv.callRealMethod();
                throw new IllegalStateException("방 정리 뒤 일시 장애");
            }).doCallRealMethod().when(challengeMemberService).leaveAllForWithdrawal(eq(JUDGE_2));
            switchOff();

            assertThatCode(() -> terminator.revokeEndedSessions()).as("한 계정 실패로 실행 전체가 터지지 않는다")
                    .doesNotThrowAnyException();

            assertThat(statusOf(JUDGE_2)).as("상태가 롤백됐다").isNotEqualTo(UserStatus.WITHDRAWN);
            assertThat(userRepository.findById(JUDGE_2).orElseThrow().getDeletedAt()).as("탈퇴 시각도 롤백됐다").isNull();
            assertThat(liveRefreshTokens(JUDGE_2)).as("RT 회수가 롤백됐다").isEqualTo(liveBefore);
            assertThat(memberStatus(roomId, JUDGE_2)).as("방 나가기가 롤백됐다").isEqualTo("ACTIVE");
            Map<String, Object> row = roomRow(roomId);
            assertThat(row.get("owner_type")).as("봇 방장 전환이 롤백됐다").isEqualTo("USER");
            assertThat(row.get("owner_id")).isNotNull();
            assertThat(JUDGES).filteredOn(j -> !j.equals(JUDGE_2))
                    .allSatisfy(j -> assertThat(statusOf(j)).as("%s", j).isEqualTo(UserStatus.WITHDRAWN));

            terminator.revokeEndedSessions();

            assertThat(statusOf(JUDGE_2)).as("다음 실행에서 다시 시도한다").isEqualTo(UserStatus.WITHDRAWN);
            assertThat(liveRefreshTokens(JUDGE_2)).isZero();
            assertThat(roomRow(roomId).get("owner_type")).isEqualTo("BOT");
            assertThat(memberStatus(roomId, realUser)).isEqualTo("ACTIVE");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("회차 — 다음 심사위원은 새 계정")
    class ReviewRound {

        @Test
        @DisplayName("같은 회차 안에서 다시 켜고 코드로 로그인하면 탈퇴 복원 경로로 같은 계정에 돌아온다")
        void sameReviewReEnableRestores() throws Exception {
            switchOffAndRunTerminator();
            clock.reset();   // 같은 회차 — JUDGE_5 의 subject 회차가 round 와 같다

            MvcResult back = codeLogin(CODE_5);

            assertThat(back.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(back, "$.data.restored")).isTrue();
            assertThat((String) read(back, "$.data.user.id")).isEqualTo(JUDGE_5.toString());
            assertThat(statusOf(JUDGE_5)).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("지난 회차 계정이 아직 ACTIVE 인 채 새 코드에 묶여도 로그인되지 않는다 — 코드 불일치와 같은 400, 토큰 없음")
        void earlierRoundActiveAccountIsRefused() throws Exception {
            assertThat(statusOf(JUDGE_8)).isNotEqualTo(UserStatus.WITHDRAWN);

            expectError(codeLogin(CODE_8), 400, "INVALID_REVIEW_CODE");

            assertThat(statusOf(JUDGE_8)).isNotEqualTo(UserStatus.WITHDRAWN);
            assertThat(liveRefreshTokens(JUDGE_8)).isZero();
        }

        @Test
        @DisplayName("지난 회차 계정이 종료가 늦어 이번 회차에 탈퇴됐어도 복원되지 않는다 — 탈퇴 시각이 아니라 계정의 회차로 가른다")
        void earlierRoundAccountWithdrawnLateIsRefused() throws Exception {
            switchOffAndRunTerminator();   // JUDGE_8 도 지금(이번 회차 중) 탈퇴된다
            clock.reset();

            expectError(codeLogin(CODE_8), 400, "INVALID_REVIEW_CODE");

            assertThat(statusOf(JUDGE_8)).isEqualTo(UserStatus.WITHDRAWN);
            assertThat(liveRefreshTokens(JUDGE_8)).isZero();
        }
    }
}
