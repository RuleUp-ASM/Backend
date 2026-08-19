package com.ruleup.ruleup_backend.challenge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 방 홈의 주간 사이클 계약 — {@code summary.weeklyCount} · {@code myWeekly} · {@code myTodayStatus}.
 *
 * <p>판정 주기는 1주 고정이고 요일 지정이 없으므로, 화면이 "이번 주 2/3"을 그리려면 방 조건(주간 횟수)과
 * 내 진행도(이번 주 성공 횟수 + 사이클 경계)가 함께 내려와야 한다. 사이클 중간에 들어온 사람은 그 주를
 * 통째로 평가받으면 불리하므로 다음 경계까지 판정 대상이 아니고, 그 상태를 화면이 "실패 중"으로 읽으면 안 된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoomWeeklyProgressIT extends ChallengeApiSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("방 조건은 주간 수행 횟수로 내려가고, 이번 주 성공 횟수가 진행도로 실린다")
    void weeklyCountAndProgress() throws Exception {
        Member me = member(uniq("weekly-progress"));
        UUID challengeId = room(me, 3, 3);          // 3일 전 시작, 주 3회
        insertSuccess(challengeId, me, 1);
        insertSuccess(challengeId, me, 2);

        JsonNode data = room(challengeId, me);
        assertThat(data.path("summary").path("weeklyCount").asInt()).isEqualTo(3);

        JsonNode weekly = data.path("myWeekly");
        assertThat(weekly.path("judging").asBoolean()).isTrue();
        assertThat(weekly.path("done").asInt()).isEqualTo(2);
        assertThat(weekly.path("weekStart").asText()).isEqualTo(today().minusDays(3).toString());
        assertThat(weekly.path("weekEnd").asText()).isEqualTo(today().plusDays(3).toString());
    }

    @Test
    @DisplayName("사이클 중간에 들어오면 다음 경계까지는 판정 대상이 아니다 — judging=false, done=0")
    void midCycleJoinIsNotJudgedYet() throws Exception {
        Member me = member(uniq("weekly-midcycle"));
        UUID challengeId = room(me, 3, 7);
        joinedToday(challengeId, me);               // 시작 3일 뒤 입장 → 다음 주부터 판정
        insertSuccess(challengeId, me, 1);          // 그 사이 성공이 있어도 이번 주 몫으로 세지 않는다

        JsonNode weekly = room(challengeId, me).path("myWeekly");
        assertThat(weekly.path("judging").asBoolean()).isFalse();
        assertThat(weekly.path("done").asInt()).isZero();
    }

    @Test
    @DisplayName("아직 시작 전인 방은 판정 대상이 아니고 오늘 상태도 NOT_TARGET 이다")
    void upcomingRoomIsNotJudged() throws Exception {
        Member me = member(uniq("weekly-upcoming"));
        UUID challengeId = insertChallenge(me.id(), "EXERCISE", "UPCOMING", "GROUP");
        insertActiveMembership(challengeId, me.id(), "OWNER");
        jdbcTemplate.update("UPDATE challenges SET start_date = DATE_ADD(CURDATE(), INTERVAL 2 DAY) WHERE id=?",
                bytes(challengeId));

        JsonNode data = room(challengeId, me);
        assertThat(data.path("myWeekly").path("judging").asBoolean()).isFalse();
        assertThat(data.path("myTodayStatus").asText()).isEqualTo("NOT_TARGET");
    }

    @Test
    @DisplayName("오늘 상태는 판정 파이프라인 어휘가 아니라 화면 어휘 5종으로 내려간다")
    void todayStatusUsesScreenVocabulary() throws Exception {
        Member me = member(uniq("weekly-today"));
        UUID challengeId = room(me, 3, 7);

        assertThat(todayStatus(challengeId, me)).isIn("IN_PROGRESS", "CHECKING");

        setTodayStatus(challengeId, me, "SUCCESS");
        assertThat(todayStatus(challengeId, me)).isEqualTo("DONE");

        // 잠정 실패는 아직 뒤집힐 수 있지만 화면에는 실패로 보여야 한다 — 별도 상태를 클라가 알 이유가 없다
        setTodayStatus(challengeId, me, "FAILED_PROVISIONAL");
        assertThat(todayStatus(challengeId, me)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("이번 주 몫을 이미 채웠으면 오늘은 대상이 아니다")
    void quotaMetMeansNotTarget() throws Exception {
        Member me = member(uniq("weekly-quota"));
        UUID challengeId = room(me, 3, 2);          // 주 2회
        insertSuccess(challengeId, me, 1);
        insertSuccess(challengeId, me, 2);

        assertThat(todayStatus(challengeId, me)).isEqualTo("NOT_TARGET");
    }

    // ===== 헬퍼 =====

    private LocalDate today() {
        return LocalDate.now(KST);
    }

    /** 내가 방장인 진행 중 방. {@code startedDaysAgo} 일 전에 시작했고 주 {@code weeklyCount} 회다. */
    private UUID room(Member me, int startedDaysAgo, int weeklyCount) {
        UUID challengeId = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, me.id(), "OWNER");
        jdbcTemplate.update("UPDATE challenges SET start_date = DATE_SUB(CURDATE(), INTERVAL ? DAY), " +
                        " weekly_count = ? WHERE id = ?",
                startedDaysAgo, weeklyCount, bytes(challengeId));
        // 방장은 시작일부터 있던 사람이다 — 중간 입장 분기를 타지 않게 가입 시각을 시작일로 맞춘다.
        jdbcTemplate.update("UPDATE challenge_members SET joined_at = " +
                        " DATE_SUB(NOW(6), INTERVAL ? DAY) WHERE challenge_id=? AND user_id=?",
                startedDaysAgo, bytes(challengeId), bytes(me.id()));
        return challengeId;
    }

    private void joinedToday(UUID challengeId, Member me) {
        jdbcTemplate.update("UPDATE challenge_members SET joined_at = NOW(6) WHERE challenge_id=? AND user_id=?",
                bytes(challengeId), bytes(me.id()));
    }

    private void setTodayStatus(UUID challengeId, Member me, String status) {
        jdbcTemplate.update("UPDATE challenge_members SET today_status=? WHERE challenge_id=? AND user_id=?",
                status, bytes(challengeId), bytes(me.id()));
    }

    private void insertSuccess(UUID challengeId, Member me, int daysAgo) {
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM challenge_members WHERE challenge_id=? AND user_id=?",
                (rs, i) -> uuidOf(rs.getBytes(1)), bytes(challengeId), bytes(me.id()));
        jdbcTemplate.update("INSERT INTO VerificationDaily " +
                        "(id, challengeMemberId, challengeId, userId, targetDate, status, verifiedAt) " +
                        "VALUES (?, ?, ?, ?, DATE_SUB(CURDATE(), INTERVAL ? DAY), 'SUCCESS', NOW(6))",
                bytes(UUID.randomUUID()), bytes(memberId), bytes(challengeId), bytes(me.id()), daysAgo);
    }

    private String todayStatus(UUID challengeId, Member me) throws Exception {
        return room(challengeId, me).path("myTodayStatus").asText();
    }

    private JsonNode room(UUID challengeId, Member me) throws Exception {
        MvcResult res = getAuth("/api/v1/challenges/" + challengeId + "/room", me.token());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return OM.readTree(res.getResponse().getContentAsString()).path("data");
    }

    private static UUID uuidOf(byte[] bytes) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
