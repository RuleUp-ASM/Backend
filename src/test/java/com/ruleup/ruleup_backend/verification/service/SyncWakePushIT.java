package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.VerificationApiSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * sync 가 끊긴 사용자를 고르는 기준 — 하루 두 번 무음 푸시로 깨울 대상.
 *
 * <p>DB 는 다른 시험과 함께 쓰므로 「정확히 이 집합」이 아니라 이 시험이 만든 사용자가 들어가는지·빠지는지만 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SyncWakePushIT extends VerificationApiSupport {

    private static final String GPS_PARAMS = "{\"duration_min\":30,\"radius_m\":100}";

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SyncWakePushService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    /** 자동 인증 방 하나에 READY 멤버로 넣고, 마지막 sync 를 {@code ago} 전으로 둔다(null 이면 sync 한 적 없음). */
    private UUID joinWithLastSync(UUID userId, Duration ago) {
        UUID challenge = insertAutoChallenge(userId, "GPS_PRESENCE", "GEOFENCE", GPS_PARAMS);
        UUID memberId = insertReadyMember(challenge, userId, anchor(37.4979, 127.0276, 100, "헬스장"), null);
        if (ago != null) {
            jdbc().update("UPDATE challenge_members SET last_synced_at = ? WHERE id = ?",
                    com.ruleup.ruleup_backend.common.DbTime.utc(Instant.now().minus(ago)), bytes(memberId));
        }
        return memberId;
    }

    @Test
    @DisplayName("마지막 sync 가 3시간 넘게 지났거나 sync 한 적이 없으면 깨운다")
    void staleOrNeverSyncedUsersAreWoken() throws Exception {
        UUID stale = member(uniq("wake-stale")).id();
        UUID never = member(uniq("wake-never")).id();
        joinWithLastSync(stale, Duration.ofHours(4));
        joinWithLastSync(never, null);

        Set<UUID> users = service.findStaleUsers();

        assertThat(users).contains(stale, never);
    }

    @Test
    @DisplayName("최근 3시간 안에 sync 했으면 깨우지 않는다")
    void recentlySyncedUserIsNotWoken() throws Exception {
        UUID fresh = member(uniq("wake-fresh")).id();
        joinWithLastSync(fresh, Duration.ofMinutes(50));

        assertThat(service.findStaleUsers()).doesNotContain(fresh);
    }

    @Test
    @DisplayName("오늘 판정이 이미 끝났으면 sync 가 끊겨도 깨우지 않는다")
    void userWhoseTodayIsSettledIsNotWoken() throws Exception {
        UUID done = member(uniq("wake-done")).id();
        UUID memberId = joinWithLastSync(done, Duration.ofHours(5));
        jdbc().update("INSERT INTO VerificationDaily (id, challengeMemberId, challengeId, userId, targetDate, status, finalizeAfter) " +
                        "SELECT ?, m.id, m.challenge_id, m.user_id, DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), 'SUCCESS', " +
                        "  DATE_ADD(UTC_TIMESTAMP(), INTERVAL 2 DAY) FROM challenge_members m WHERE m.id = ?",
                bytes(UUID.randomUUID()), bytes(memberId));

        assertThat(service.findStaleUsers()).doesNotContain(done);
    }

    @Test
    @DisplayName("다른 방에서 최근 sync 가 찍혔으면 앱은 살아 있다 — 깨우지 않는다")
    void userAliveInAnotherRoomIsNotWoken() throws Exception {
        UUID twoRooms = member(uniq("wake-two")).id();
        joinWithLastSync(twoRooms, null);                       // 오늘 들어온 방 — sync 기록이 아직 없다
        joinWithLastSync(twoRooms, Duration.ofMinutes(20));     // 다른 방은 방금 sync 됐다

        assertThat(service.findStaleUsers()).doesNotContain(twoRooms);
    }
}
