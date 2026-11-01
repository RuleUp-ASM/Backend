package com.ruleup.ruleup_backend.observability.usage;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.common.DbTime;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.AutoVerification;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.RoomCell;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.RoomComposition;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 서비스 이용 집계 — 방 구성·자동 인증·관심사·티어·탈퇴 단계·화면 조회.
 *
 * <p>테스트 DB 는 스위트 전체가 공유해 다른 시험이 만든 회원·방이 섞여 있다. 그래서 절댓값이 아니라 <b>심기 전·후의 차이</b>로
 * 단언한다({@link com.ruleup.ruleup_backend.admin.MemberProfileIT} 와 같다).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UsageStatsIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UsageStatsService stats;
    @Autowired Clock clock;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() { return mvc; }

    @Override
    protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @Test
    @DisplayName("방 구성 — 모드·인증 방식·상태별 방 수와 인원, 끝난 방·나간 멤버·운영자는 빼고, 사람 수는 모드마다 중복 없이")
    void roomComposition() throws Exception {
        RoomComposition before = stats.rooms();

        Member a = member(uniq("rca"));   // 그룹 자동 방장 + 솔로 수동
        Member b = member(uniq("rcb"));   // 그룹 자동 멤버
        Member c = member(uniq("rcc"));   // 그룹 자동에서 나감 — 안 센다
        Member op = member(uniq("rco"));  // 운영자 — 회원이 아니다
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(op.id()));

        UUID groupAuto = room(a.id(), "ACTIVE", "GROUP", "AUTO");
        insertActiveMembership(groupAuto, a.id(), "OWNER");
        insertActiveMembership(groupAuto, b.id(), "MEMBER");
        insertActiveMembership(groupAuto, c.id(), "MEMBER");
        jdbcTemplate.update("UPDATE challenge_members SET status = 'LEFT' WHERE challenge_id = ? AND user_id = ?",
                bytes(groupAuto), bytes(c.id()));
        UUID soloManual = room(a.id(), "UPCOMING", "SOLO", null);     // verification_type 이 빈 옛 행 → 설정의 MANUAL
        insertActiveMembership(soloManual, a.id(), "OWNER");
        insertActiveMembership(room(a.id(), "COMPLETED", "GROUP", "AUTO"), b.id(), "MEMBER");  // 끝난 방
        insertActiveMembership(room(op.id(), "ACTIVE", "GROUP", "MANUAL"), op.id(), "OWNER");  // 운영자만 있는 방

        RoomComposition after = stats.rooms();

        assertThat(cell(after, "GROUP", "AUTO", "ACTIVE").rooms() - cell(before, "GROUP", "AUTO", "ACTIVE").rooms()).isEqualTo(1);
        assertThat(cell(after, "GROUP", "AUTO", "ACTIVE").memberships() - cell(before, "GROUP", "AUTO", "ACTIVE").memberships())
                .as("나간 멤버는 빼고 2명").isEqualTo(2);
        assertThat(cell(after, "SOLO", "MANUAL", "UPCOMING").rooms() - cell(before, "SOLO", "MANUAL", "UPCOMING").rooms()).isEqualTo(1);
        assertThat(cell(after, "GROUP", "MANUAL", "ACTIVE").rooms() - cell(before, "GROUP", "MANUAL", "ACTIVE").rooms())
                .as("운영자만 있는 방은 안 센다").isZero();
        assertThat(after.people().group() - before.people().group()).isEqualTo(2);
        assertThat(after.people().solo() - before.people().solo()).isEqualTo(1);
        assertThat(after.people().auto() - before.people().auto()).isEqualTo(2);
        assertThat(after.people().manual() - before.people().manual()).isEqualTo(1);
        assertThat(after.people().total() - before.people().total()).as("a 는 두 칸에 다 있어도 한 명").isEqualTo(2);
    }

    @Test
    @DisplayName("자동 인증 — 최근 sync 보낸 사람, 진행 상태(셋업·권한·sync 없음·끊김·막 들어옴), 확정 판정과 사람별 성공 경험")
    void autoVerification() throws Exception {
        AutoVerification before = stats.autoVerification();
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));

        Member owner = member(uniq("avo"));
        Member ok = member(uniq("avk"));          // 1시간 전 sync
        Member setup = member(uniq("avs"));       // 셋업 안 함
        Member perm = member(uniq("avp"));        // 권한 대기 — sync 가 있어도 권한이 먼저
        Member never = member(uniq("avn"));       // 사흘 전에 들어와 sync 없음
        Member fresh = member(uniq("avf"));       // 방금 들어와 sync 없음
        Member stale = member(uniq("avt"));       // 사흘 전 sync 뒤 끊김
        UUID room = room(owner.id(), "ACTIVE", "GROUP", "AUTO");
        insertActiveMembership(room, owner.id(), "OWNER");
        for (Member m : new Member[]{ok, setup, perm, never, fresh, stale}) insertActiveMembership(room, m.id(), "MEMBER");
        jdbcTemplate.update("UPDATE challenge_members SET setup_status = 'READY', joined_at = ? WHERE challenge_id = ?",
                DbTime.utc(now.minus(Duration.ofDays(3))), bytes(room));
        syncedAt(room, owner.id(), now.minus(Duration.ofDays(3)));
        syncedAt(room, ok.id(), now.minus(Duration.ofHours(1)));
        syncedAt(room, perm.id(), now.minus(Duration.ofHours(1)));
        syncedAt(room, stale.id(), now.minus(Duration.ofDays(3)));
        jdbcTemplate.update("UPDATE challenge_members SET setup_status = 'PENDING_SETUP' WHERE challenge_id = ? AND user_id = ?",
                bytes(room), bytes(setup.id()));
        jdbcTemplate.update("UPDATE challenge_members SET joined_at = ? WHERE challenge_id = ? AND user_id = ?",
                DbTime.utc(now.minus(Duration.ofHours(2))), bytes(room), bytes(fresh.id()));
        jdbcTemplate.update("INSERT INTO verification_permission_waits (challenge_id, user_id, signal_type, source_event_id,"
                        + " first_observed_at, waiting_from_on) VALUES (?, ?, 'HEALTH', ?, ?, ?)",
                bytes(room), bytes(perm.id()), bytes(UUID.randomUUID()), DbTime.utc(now), today);

        // 판정 — ok: 성공 1·측정 실패 1 / stale: 측정 실패 1·신호 없음 1 / never: 권한 없음 1 / 범위 밖 날짜는 안 센다
        judgment(room, ok.id(), today.minusDays(2), "SUCCESS", null);
        judgment(room, ok.id(), today.minusDays(3), "FAILED", null);
        judgment(room, stale.id(), today.minusDays(4), "FAILED", null);
        judgment(room, stale.id(), today.minusDays(5), "FAILED", "NO_SIGNAL");
        judgment(room, never.id(), today.minusDays(8), "FAILED", "PERMISSION_MISSING");
        judgment(room, ok.id(), today.minusDays(1), "SUCCESS", null);     // 아직 확정 전
        judgment(room, ok.id(), today.minusDays(9), "SUCCESS", null);     // 7일 밖

        AutoVerification after = stats.autoVerification();

        assertThat(after.inRoom() - before.inRoom()).isEqualTo(7);
        assertThat(after.synced1d() - before.synced1d()).isEqualTo(2);
        assertThat(after.synced7d() - before.synced7d()).isEqualTo(4);
        assertThat(diff(before.stall(), after.stall())).isEqualTo(Map.of(
                "ok", 1L, "pending_setup", 1L, "permission_missing", 1L, "just_joined", 1L,
                "never_synced", 1L, "sync_stale", 2L));
        assertThat(diff(before.judgments(), after.judgments())).isEqualTo(Map.of(
                "success", 1L, "failed_measured", 2L, "failed_no_signal", 1L, "failed_permission", 1L));
        assertThat(after.succeededPeople() - before.succeededPeople()).isEqualTo(1);
        assertThat(after.failedOnlyPeople() - before.failedOnlyPeople())
                .as("stale 은 측정 실패만, never 는 측정된 판정이 없어 안 센다").isEqualTo(1);
    }

    // ===== 내부 =====

    private void syncedAt(UUID room, UUID userId, Instant at) {
        jdbcTemplate.update("UPDATE challenge_members SET last_synced_at = ? WHERE challenge_id = ? AND user_id = ?",
                DbTime.utc(at), bytes(room), bytes(userId));
    }

    private void judgment(UUID room, UUID userId, LocalDate date, String status, String gapReason) {
        jdbcTemplate.update("INSERT INTO VerificationDaily (id, challengeMemberId, challengeId, userId, targetDate, status, gapReason)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                bytes(UUID.randomUUID()), bytes(UUID.randomUUID()), bytes(room), bytes(userId), date, status, gapReason);
    }

    private static Map<String, Long> diff(Map<String, Long> before, Map<String, Long> after) {
        return after.entrySet().stream()
                .filter(e -> !e.getValue().equals(before.getOrDefault(e.getKey(), 0L)))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue() - before.getOrDefault(e.getKey(), 0L)));
    }

    private UUID room(UUID ownerId, String status, String mode, String verificationType) {
        UUID id = insertChallenge(ownerId, "EXERCISE", status, mode);
        jdbcTemplate.update("UPDATE challenges SET verification_type = ? WHERE id = ?", verificationType, bytes(id));
        return id;
    }

    private static RoomCell cell(RoomComposition r, String mode, String verification, String status) {
        return r.cells().stream()
                .filter(c -> c.mode().equals(mode) && verification.equals(c.verification()) && c.status().equals(status))
                .findFirst().orElse(new RoomCell(mode, verification, status, 0, 0));
    }
}
