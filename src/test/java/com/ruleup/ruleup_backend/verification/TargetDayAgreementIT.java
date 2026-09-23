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
