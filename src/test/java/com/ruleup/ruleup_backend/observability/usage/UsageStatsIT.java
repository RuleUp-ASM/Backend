package com.ruleup.ruleup_backend.observability.usage;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.common.DbTime;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.AutoVerification;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.MemberUsage;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.RoomCell;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.ScreenViewers;
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
    @Autowired ScreenViewRecorder recorder;

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

    @Test
    @DisplayName("회원 이용 — 표시 티어, 참여 중인 방이 관심사 안·밖·섞임·관심사 없음, 탈퇴자의 이용 단계")
    void memberUsage() throws Exception {
        MemberUsage before = stats.members();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));

        Member in = member(uniq("mui"));      // 관심사 EXERCISE·READING(가입 기본값), 운동 방만 → 안
        Member mix = member(uniq("mum"));     // 운동 + 학습 → 섞임
        Member out = member(uniq("muo"));     // 학습 방만 → 밖
        Member none = member(uniq("mun"));    // 관심사 없음, 운동 방
        Member idle = member(uniq("mux"));    // 방 없음 — 관심사 칸에 안 든다
        jdbcTemplate.update("UPDATE user_score_summaries SET display_tier = 'SILVER' WHERE user_id = ?", bytes(in.id()));
        jdbcTemplate.update("DELETE FROM user_interests WHERE user_id = ?", bytes(none.id()));
        UUID exercise = insertChallenge(idle.id(), "EXERCISE", "ACTIVE", "GROUP");
        UUID study = insertChallenge(idle.id(), "STUDY", "UPCOMING", "GROUP");
        insertActiveMembership(exercise, in.id(), "MEMBER");
        insertActiveMembership(exercise, mix.id(), "MEMBER");
        insertActiveMembership(study, mix.id(), "MEMBER");
        insertActiveMembership(study, out.id(), "MEMBER");
        insertActiveMembership(exercise, none.id(), "MEMBER");

        Member w1 = member(uniq("mw1"));      // 방에 안 들어가고 탈퇴
        Member w2 = member(uniq("mw2"));      // 방에 들어갔다 판정 없이 탈퇴
        Member w3 = member(uniq("mw3"));      // 판정까지 받고 탈퇴
        insertActiveMembership(exercise, w2.id(), "MEMBER");
        insertActiveMembership(exercise, w3.id(), "MEMBER");
        judgment(exercise, w3.id(), today.minusDays(3), "SUCCESS", null);
        for (Member w : new Member[]{w1, w2, w3}) {
            jdbcTemplate.update("UPDATE challenge_members SET status = 'LEFT' WHERE user_id = ?", bytes(w.id()));
            jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = created_at + INTERVAL 2 HOUR WHERE id = ?",
                    bytes(w.id()));
        }

        MemberUsage after = stats.members();

        assertThat(diff(before.tiers(), after.tiers()))
                .as("새 회원 5명 중 SILVER 1, 나머지는 가입 기본 BRONZE — 탈퇴자 3명은 빠진다").isEqualTo(Map.of("BRONZE", 4L, "SILVER", 1L));
        assertThat(after.tiers().keySet()).as("티어 칸은 0 이어도 다").contains("UNRANKED", "RUBY");
        assertThat(diff(before.interestFit(), after.interestFit())).isEqualTo(Map.of(
                "interest_only", 1L, "mixed", 1L, "outside_only", 1L, "no_interest", 1L));
        assertThat(diff(before.withdrawalStage(), after.withdrawalStage())).isEqualTo(Map.of(
                "before_room", 1L, "no_judgment", 1L, "after_judgment", 1L));
    }

    @Test
    @DisplayName("화면 조회 — 성공한 화면 API 만 사람·화면·날짜 하나로 남고, 남의 방(403)·운영자는 안 센다")
    void screenViews() throws Exception {
        ScreenViewers before = stats.screens();
        Member a = member(uniq("sva"));
        Member b = member(uniq("svb"));
        Member op = member(uniq("svo"));
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(op.id()));
        UUID room = insertChallenge(a.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(room, a.id(), "OWNER");

        assertThat(getAuth("/api/v1/challenges/" + room + "/threads", a.token()).getResponse().getStatus()).isEqualTo(200);
        assertThat(getAuth("/api/v1/challenges/" + room + "/threads?size=5", a.token()).getResponse().getStatus()).isEqualTo(200);
        assertThat(getAuth("/api/v1/me/stats", a.token()).getResponse().getStatus()).isEqualTo(200);
        String month = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString().substring(0, 7);
        assertThat(getAuth("/api/v1/me/calendar?month=" + month, a.token()).getResponse().getStatus()).isEqualTo(200);
        assertThat(getAuth("/api/v1/me/calendar", a.token()).getResponse().getStatus())
                .as("잘못된 요청(400)은 안 센다 — 위 200 과 같은 화면이라 결과에는 영향이 없다").isEqualTo(400);
        assertThat(getAuth("/api/v1/me/stats", b.token()).getResponse().getStatus()).isEqualTo(200);
        assertThat(getAuth("/api/v1/challenges/" + room + "/threads", b.token()).getResponse().getStatus())
                .as("멤버가 아니면 403 — 화면을 본 게 아니다").isEqualTo(403);
        assertThat(getAuth("/api/v1/me/stats", op.token()).getResponse().getStatus()).isEqualTo(200);
        recorder.forget();   // 메모리가 비어도 같은 날 같은 화면은 한 행(INSERT IGNORE)
        getAuth("/api/v1/me/stats", a.token());

        ScreenViewers after = stats.screens();

        assertThat(diff(before.today(), after.today())).isEqualTo(Map.of("feed", 1L, "stats", 2L, "calendar", 1L));
        assertThat(after.last7d().get("stats") - before.last7d().get("stats")).isEqualTo(2);
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_screen_views WHERE user_id = ?",
                Integer.class, bytes(a.id()));
        assertThat(rows).isEqualTo(3);
    }

    @Test
    @DisplayName("화면 조회 정리 — 보관 기간이 지난 날짜만 지운다")
    void purgesOldViews() {
        UUID u = UUID.randomUUID();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        jdbcTemplate.update("INSERT INTO user_screen_views (view_date, screen, user_id) VALUES (?, 'home', ?), (?, 'home', ?)",
                today.minusDays(ScreenViewRecorder.RETENTION_DAYS + 1), bytes(u),
                today.minusDays(ScreenViewRecorder.RETENTION_DAYS), bytes(u));

        recorder.purge();

        assertThat(jdbcTemplate.queryForList("SELECT view_date FROM user_screen_views WHERE user_id = ?", LocalDate.class, bytes(u)))
                .containsExactly(today.minusDays(ScreenViewRecorder.RETENTION_DAYS));
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
