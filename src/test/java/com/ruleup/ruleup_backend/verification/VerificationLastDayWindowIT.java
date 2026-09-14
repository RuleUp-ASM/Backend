package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeAutoDeleteService;
import com.ruleup.ruleup_backend.verification.service.VerificationFinalizeService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 챌린지 <b>마지막 활동일</b>의 유예·확정이 살아 있는지 검증한다.
 *
 * <p>여기가 무너지기 쉬운 이유는 두 도메인의 시계가 다르기 때문이다.
 * <ul>
 *   <li>챌린지: {@code endDate} 다음 날 ACTIVE → COMPLETED, 그 뒤 자동 삭제</li>
 *   <li>인증: {@code endDate} 귀속 판정의 확정은 <b>이틀 뒤</b> 00:00 KST</li>
 * </ul>
 * 방이 먼저 사라지면 마지막 날은 늦게 도착한 신호를 반영할 수도, 실제 기준으로 판정할 수도 없다 —
 * 모든 챌린지의 마지막 날이 통째로 오판정된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationLastDayWindowIT extends VerificationApiSupport {

    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VerificationFinalizeService finalizeService;
    @Autowired ChallengeAutoDeleteService autoDeleteService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static String visitParams() { return "{\"duration_min\":30,\"radius_m\":100}"; }

    /** 종료 배치가 이미 돈 방 — 마지막 활동일이 {@code daysAgo} 일 전이고 상태는 COMPLETED 다. */
    private void endedDaysAgo(UUID challengeId, int daysAgo) {
        jdbc().update("UPDATE challenges SET status = 'COMPLETED', " +
                        " start_date = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY), " +
                        " end_date = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY) " +
                        "WHERE id = ?",
                daysAgo + 10, daysAgo, bytes(challengeId));
    }

    private String statusOn(UUID challengeMemberId, LocalDate date) {
        List<String> rows = jdbc().queryForList(
                "SELECT status FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                String.class, bytes(challengeMemberId), java.sql.Date.valueOf(date));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean challengeExists(UUID challengeId) {
        return !jdbc().queryForList("SELECT 1 FROM challenges WHERE id = ?",
                Integer.class, bytes(challengeId)).isEmpty();
    }

    @Test
    @DisplayName("[P0] 방이 COMPLETED 로 바뀐 뒤에도 마지막 활동일의 늦은 신호가 반영된다")
    void lateSignalOfTheFinalDayStillCountsAfterCompletion() throws Exception {
        Member me = member(uniq("lastday-late"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        endedDaysAgo(challenge, 1);   // 어제가 마지막 활동일 → 오늘은 유예 구간

        // 어제 다녀온 기록이 절전으로 밀렸다가 오늘 올라온다.
        assertThat(postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(
                geofenceSignal(memberId, "ENTER", todayAt(9, 0).minusSeconds(86_400)),
                geofenceSignal(memberId, "EXIT", todayAt(10, 0).minusSeconds(86_400)))))
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(statusOn(memberId, LocalDate.now(KST).minusDays(1)))
                .as("ACTIVE 방만 평가하면 모든 챌린지의 마지막 날이 유예를 못 받는다")
                .isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("[P0] 마지막 활동일도 확정 배치가 집어 실제 사유로 확정한다")
    void theFinalDayIsFinalizedByTheBatchWithItsRealReason() throws Exception {
        Member me = member(uniq("lastday-finalize"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        endedDaysAgo(challenge, 2);   // 그저께가 마지막 활동일 → 오늘 00:00 이 확정 시각

        finalizeService.materializeDueTargets();
        finalizeService.finalizeDue();

        assertThat(statusOn(memberId, LocalDate.now(KST).minusDays(2)))
                .as("COMPLETED 방을 건너뛰면 마지막 날이 통계에서 사라진다")
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("[P0] 자동 삭제는 마지막 활동일의 확정이 끝난 뒤에만 방을 지운다")
    void autoDeleteWaitsUntilTheFinalDayIsConfirmed() throws Exception {
        Member stillOpen = member(uniq("lastday-keep"));
        UUID openRoom = insertAutoChallenge(stillOpen.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        insertReadyMember(openRoom, stillOpen.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        endedDaysAgo(openRoom, 1);    // 아직 유예 중

        Member settled = member(uniq("lastday-drop"));
        UUID settledRoom = insertAutoChallenge(settled.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        insertReadyMember(settledRoom, settled.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        endedDaysAgo(settledRoom, 4); // 확정이 끝난 지 이틀

        autoDeleteService.runOnce();

        assertThat(challengeExists(openRoom))
                .as("확정 전에 지우면 D+2 확정기가 판정 기준을 잃는다")
                .isTrue();
        assertThat(challengeExists(settledRoom))
                .as("확정이 끝난 방까지 붙잡아 두면 정리가 영영 안 된다")
                .isFalse();
    }
}
