package com.ruleup.ruleup_backend.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * 「오늘이 인증하는 날인가」는 한 가지 답만 있어야 한다 — QA MAN-13 · VER-03.
 *
 * <p>같은 순간에 방 상세는 {@code NOT_TARGET}, {@code /verifications/today} 는 {@code IN_PROGRESS}
 * 를 내놓았고, 그 위에서 수동 제출까지 통과해 {@code DONE} 이 찍혔다. 세 경로가 각자 규칙을
 * 들고 있었기 때문이다. 이 스위트는 셋이 같은 답을 내는지만 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TargetDayAgreementIT extends ChallengeApiSupport {

    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired com.ruleup.ruleup_backend.verification.service.VerificationFinalizeService finalizeService;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("주 몫을 채우면 방·today·수동 제출이 모두 「오늘은 아니다」로 답한다")
    void quotaMetAgreesEverywhere() throws Exception {
        Member me = member(uniq("tda-met"));
        UUID challengeId = manualWeeklyRoom(me, 2);     // 주 2회, 3일 전 시작
        seedSuccess(me, challengeId, 1);
        seedSuccess(me, challengeId, 2);

        assertThat(roomTodayStatus(challengeId, me)).isEqualTo("NOT_TARGET");
        assertThat(todayStatus(challengeId, me)).isEqualTo("NOT_TARGET");

        // 화면이 막아도 요청은 올 수 있다 — 막는 쪽은 서버여야 한다.
        MvcResult blocked = postJsonAuth("/api/v1/challenges/" + challengeId + "/verifications",
                me.token(), Map.of());
        expectError(blocked, 409, "NOT_TARGET_DATE");
    }

    @Test
    @DisplayName("몫이 남았으면 체크가 되고, 그 체크가 두 화면의 답을 같이 바꾼다")
    void submitAndCancelMoveBothViews() throws Exception {
        Member me = member(uniq("tda-open"));
        UUID challengeId = manualWeeklyRoom(me, 3);     // 주 3회
        seedSuccess(me, challengeId, 1);
        seedSuccess(me, challengeId, 2);

        assertThat(roomTodayStatus(challengeId, me)).isEqualTo("IN_PROGRESS");
        assertThat(todayStatus(challengeId, me)).isEqualTo("IN_PROGRESS");

        MvcResult done = postJsonAuth("/api/v1/challenges/" + challengeId + "/verifications",
                me.token(), Map.of());
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        String verificationId = read(done, "$.data.verificationId");

        assertThat(roomTodayStatus(challengeId, me)).isEqualTo("DONE");
        assertThat(todayStatus(challengeId, me)).isEqualTo("DONE");
        // 세 번째 체크로 주 몫이 찼다 — 카운터가 실제로 올라갔는지는 여기서 드러난다.
        assertThat(periodCompleted(challengeId, me)).isEqualTo(3);

        mvc.perform(delete("/api/v1/verifications/" + verificationId)
                .header("Authorization", "Bearer " + me.token())).andReturn();

        // 되돌렸으면 다시 할 수 있어야 한다. 카운터를 안 깎으면 그 주는 영영 잠긴다.
        assertThat(periodCompleted(challengeId, me)).isEqualTo(2);
        assertThat(roomTodayStatus(challengeId, me)).isEqualTo("IN_PROGRESS");
        assertThat(todayStatus(challengeId, me)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("진행 중인 방에 들어온 날은 방·today·수동 제출이 모두 「오늘은 아니다」, 다음 날부터 판정한다(QA JOIN-14)")
    void midJoinStartsNextDay() throws Exception {
        Member me = member(uniq("tda-midjoin"));
        UUID challengeId = manualWeeklyRoom(me, 7);
        // 방은 3일 전에 시작했고 나는 방금 들어왔다.
        jdbcTemplate.update("UPDATE challenge_members SET role='MEMBER', joined_at = UTC_TIMESTAMP(6) " +
                "WHERE challenge_id=? AND user_id=?", bytes(challengeId), bytes(me.id()));
        jdbcTemplate.update("UPDATE challenges SET owner_id = ? WHERE id = ?",
                bytes(member(uniq("tda-midjoin-owner")).id()), bytes(challengeId));

        assertThat(roomTodayStatus(challengeId, me)).isEqualTo("NOT_TARGET");
        assertThat(todayStatus(challengeId, me)).isEqualTo("NOT_TARGET");
        expectError(postJsonAuth("/api/v1/challenges/" + challengeId + "/verifications", me.token(), Map.of()),
                409, "NOT_TARGET_DATE");

        // 어제 들어왔다면 오늘은 판정 대상이다.
        jdbcTemplate.update("UPDATE challenge_members SET joined_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY) " +
                "WHERE challenge_id=? AND user_id=?", bytes(challengeId), bytes(me.id()));
        assertThat(roomTodayStatus(challengeId, me)).isEqualTo("IN_PROGRESS");
        assertThat(todayStatus(challengeId, me)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("자정~롤오버(00:05) 사이 새 주기 첫날은 지난주 몫과 무관하게 판정 대상이고, 그날 성공은 새 주기로 넘어간다(QA VER-13)")
    void newPeriodBeforeRollover() throws Exception {
        Member me = member(uniq("tda-rollover"));
        UUID challengeId = manualWeeklyRoom(me, 2);
        // 지난 주기(9일 전~어제)는 몫을 채웠고, 롤오버는 아직 돌지 않았다.
        jdbcTemplate.update("UPDATE challenges SET start_date = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 7 DAY) WHERE id = ?",
                bytes(challengeId));
        // 지난주 마지막 sync 가 남긴 캐시(today_status=NOT_REQUIRED)도 그대로 둔다 — 방 홈이 이 값을 날짜 확인 없이 쓰면 안 된다.
        jdbcTemplate.update("UPDATE challenge_members SET joined_at = DATE_SUB(NOW(6), INTERVAL 8 DAY), cur_period_completed = 2," +
                        " today_status = 'NOT_REQUIRED', last_synced_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY)," +
                        " cur_period_start = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 7 DAY)," +
                        " cur_period_end = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 1 DAY)" +
                        " WHERE challenge_id=? AND user_id=?", bytes(challengeId), bytes(me.id()));

        assertThat(roomTodayStatus(challengeId, me)).isEqualTo("IN_PROGRESS");
        assertThat(todayStatus(challengeId, me)).isEqualTo("IN_PROGRESS");

        assertThat(postJsonAuth("/api/v1/challenges/" + challengeId + "/verifications", me.token(), Map.of())
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(periodCompleted(challengeId, me)).as("새 주기 성공을 지난 주기 카운터에 더하지 않는다").isEqualTo(2);

        finalizeService.rolloverFrequencyPeriods();
        assertThat(periodCompleted(challengeId, me)).as("롤오버가 새 주기 성공을 판정 행으로 다시 센다").isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 하루만 판정 대상인 중간 가입자가 그 하루를 성공하면 롤오버 뒤 실패가 0 이다(리뷰 지적, QA JOIN-14)")
    void midJoinLastDayOnlyHasNoFailures() throws Exception {
        Member me = member(uniq("tda-lastday"));
        UUID challengeId = manualWeeklyRoom(me, 7);
        // 방은 7일 전~어제 한 주짜리, 나는 그제 들어왔으니 판정은 어제 하루뿐이다.
        jdbcTemplate.update("UPDATE challenges SET start_date = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 7 DAY)," +
                " end_date = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 1 DAY) WHERE id = ?", bytes(challengeId));
        jdbcTemplate.update("UPDATE challenge_members SET joined_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY), cur_period_completed = 0," +
                        " cur_period_start = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 7 DAY)," +
                        " cur_period_end = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 1 DAY)," +
                        " fail_days = 0, success_days = 0 WHERE challenge_id=? AND user_id=?", bytes(challengeId), bytes(me.id()));
        seedSuccess(me, challengeId, 1);

        finalizeService.rolloverFrequencyPeriods();

        assertThat(failDays(challengeId, me)).as("가입 전 6일을 미달로 정산하면 안 된다").isZero();
    }

    @Test
    @DisplayName("20일째 방에 어제 들어와 오늘 성공하면 롤오버가 가입 전 주기를 미달로 따라잡지 않는다(리뷰 지적, QA JOIN-14)")
    void midJoinDoesNotCatchUpPreJoinPeriods() throws Exception {
        Member me = member(uniq("tda-catchup"));
        UUID challengeId = manualWeeklyRoom(me, 7);
        jdbcTemplate.update("UPDATE challenges SET start_date = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 20 DAY)," +
                " end_date = DATE_ADD(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 20 DAY) WHERE id = ?", bytes(challengeId));
        // 수정 전 셋업처럼 현재 주기가 챌린지 첫 주기로 잡혀 있는 상태.
        jdbcTemplate.update("UPDATE challenge_members SET joined_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY), cur_period_completed = 0," +
                        " cur_period_start = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 20 DAY)," +
                        " cur_period_end = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 14 DAY)," +
                        " fail_days = 0, success_days = 0 WHERE challenge_id=? AND user_id=?", bytes(challengeId), bytes(me.id()));
        assertThat(postJsonAuth("/api/v1/challenges/" + challengeId + "/verifications", me.token(), Map.of())
                .getResponse().getStatus()).isEqualTo(200);

        finalizeService.rolloverFrequencyPeriods();

        assertThat(failDays(challengeId, me)).as("가입 전 두 주를 미달로 쌓으면 안 된다").isZero();
        assertThat(periodCompleted(challengeId, me)).isEqualTo(1);
    }

    @Test
    @DisplayName("종료일 없는 방에 늦게 들어오면 현재 참여 주기부터 셋업하고, 가입 전 주기로 분모를 늘리지 않는다(리뷰 지적)")
    void unlimitedLateJoinTargetsOnlyParticipation() throws Exception {
        Member me = member(uniq("tda-unlimited"));
        UUID challengeId = lateJoinRoom(me, 20, null);

        assertThat(postJsonAuth("/api/v1/challenges/" + challengeId + "/verifications", me.token(), Map.of())
                .getResponse().getStatus()).isEqualTo(200);
        finalizeService.rolloverFrequencyPeriods();

        assertThat(jdbcTemplate.queryForObject("SELECT target_days FROM challenge_members WHERE challenge_id=? AND user_id=?",
                Integer.class, bytes(challengeId), bytes(me.id())))
                .as("현재 주기에 남은 대상일은 오늘 하루다 — 가입 전 2주가 분모에 들어가면 안 된다").isEqualTo(1);
        assertThat(failDays(challengeId, me)).isZero();
    }

    @Test
    @DisplayName("잘린 주기의 진행 표시(period.target/remaining)도 판정과 같은 몫을 쓴다(리뷰 지적)")
    void partialPeriodProgressUsesActualQuota() throws Exception {
        Member me = member(uniq("tda-progress"));
        UUID challengeId = lateJoinRoom(me, 6, 0);   // 6일 전~오늘 한 주, 어제 가입 → 오늘 하루만 대상

        assertThat(postJsonAuth("/api/v1/challenges/" + challengeId + "/verifications", me.token(), Map.of())
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult progress = getAuth("/api/v1/verifications/progress", me.token());
        java.util.List<Map<String, Object>> rows = read(progress, "$.data.challenges");
        Map<String, Object> period = rows.stream().filter(r -> challengeId.toString().equals(r.get("challengeId")))
                .findFirst().map(r -> (Map<String, Object>) r.get("period")).orElseThrow();
        assertThat(period).containsEntry("target", 1).containsEntry("completed", 1).containsEntry("remaining", 0);
    }

    /** 주 7회 수동 방. 어제 가입했고 셋업 전(target_days=0)이라 첫 체크가 셋업을 부른다. */
    private UUID lateJoinRoom(Member me, int startDaysAgo, Integer endDaysFromNow) {
        UUID challengeId = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, me.id(), "MEMBER");
        jdbcTemplate.update("UPDATE challenges SET weekly_count = 7," +
                        " start_date = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY)," +
                        " end_date = " + (endDaysFromNow == null ? "NULL, duration_days = NULL"
                        : "DATE_ADD(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL " + endDaysFromNow + " DAY)") +
                        " WHERE id = ?", startDaysAgo, bytes(challengeId));
        jdbcTemplate.update("UPDATE challenge_members SET joined_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY)," +
                " setup_status = 'READY', target_days = 0 WHERE challenge_id = ? AND user_id = ?", bytes(challengeId), bytes(me.id()));
        return challengeId;
    }

    // ===== 헬퍼 =====

    /** 3일 전 시작한 수동·빈도형 방. 주기 필드는 셋업이 채우는 모양 그대로 둔다. */
    private UUID manualWeeklyRoom(Member me, int weeklyCount) {
        UUID challengeId = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, me.id(), "OWNER");
        jdbcTemplate.update("UPDATE challenges SET weekly_count = ?,"
                        + " start_date = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 3 DAY)"
                        + " WHERE id = ?",
                weeklyCount, bytes(challengeId));
        jdbcTemplate.update("UPDATE challenge_members SET joined_at = DATE_SUB(NOW(6), INTERVAL 3 DAY),"
                        + " schedule_type='FREQUENCY', period_unit='WEEK', period_target=?, target_days=?,"
                        + " setup_status='READY', cur_period_completed=0,"
                        + " cur_period_start = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 3 DAY),"
                        + " cur_period_end = DATE_ADD(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 3 DAY)"
                        + " WHERE challenge_id=? AND user_id=?",
                weeklyCount, weeklyCount, bytes(challengeId), bytes(me.id()));
        return challengeId;
    }

    /** 지난 날의 성공 한 건 — 판정 경로가 남기는 것(판정 행 + 주기 카운터)을 함께 남긴다. */
    private void seedSuccess(Member me, UUID challengeId, int daysAgo) {
        UUID memberId = memberId(challengeId, me);
        jdbcTemplate.update("INSERT INTO VerificationDaily"
                        + " (id, challengeMemberId, challengeId, userId, targetDate, status, verifiedAt)"
                        + " VALUES (?, ?, ?, ?,"
                        + " DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY),"
                        + " 'SUCCESS', NOW(6))",
                bytes(UUID.randomUUID()), bytes(memberId), bytes(challengeId), bytes(me.id()), daysAgo);
        jdbcTemplate.update("UPDATE challenge_members SET cur_period_completed = cur_period_completed + 1,"
                        + " success_days = success_days + 1 WHERE id = ?", bytes(memberId));
    }

    private UUID memberId(UUID challengeId, Member me) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM challenge_members WHERE challenge_id=? AND user_id=?",
                (rs, i) -> uuidOf(rs.getBytes(1)), bytes(challengeId), bytes(me.id()));
    }

    private int failDays(UUID challengeId, Member me) {
        return jdbcTemplate.queryForObject(
                "SELECT fail_days FROM challenge_members WHERE challenge_id=? AND user_id=?",
                Integer.class, bytes(challengeId), bytes(me.id()));
    }

    private int periodCompleted(UUID challengeId, Member me) {
        return jdbcTemplate.queryForObject(
                "SELECT cur_period_completed FROM challenge_members WHERE challenge_id=? AND user_id=?",
                Integer.class, bytes(challengeId), bytes(me.id()));
    }

    private String roomTodayStatus(UUID challengeId, Member me) throws Exception {
        MvcResult res = getAuth("/api/v1/challenges/" + challengeId + "/room", me.token());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return OM.readTree(res.getResponse().getContentAsString())
                .path("data").path("myTodayStatus").asText();
    }

    private String todayStatus(UUID challengeId, Member me) throws Exception {
        MvcResult res = getAuth("/api/v1/challenges/" + challengeId + "/verifications/today", me.token());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return read(res, "$.data.status");
    }

    private static UUID uuidOf(byte[] raw) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
