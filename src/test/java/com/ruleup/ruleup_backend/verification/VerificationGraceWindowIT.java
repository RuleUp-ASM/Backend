package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.service.VerificationFinalizeService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 유예 구간(D+1\~D+2)이 실제로 동작하는지 검증한다.
 *
 * <p>확정을 귀속일 이틀 뒤로 미룬 이유는 <b>귀속일 당일에 sync 가 한 번도 없었어도</b> 나중에 올라온 기록을
 * 인정하기 위해서다. 그런데 판정 행은 sync 가 만들기 때문에, "그날 앱을 아예 안 켠" 사용자는
 * <ul>
 *   <li>다음 날 신호를 올려도 붙일 행이 없어 평가되지 않고,</li>
 *   <li>끝내 아무 신호도 없으면 실패로 확정되지도 않아 통계에서 통째로 사라진다.</li>
 * </ul>
 * 둘 다 유예 구간을 이름뿐인 것으로 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationGraceWindowIT extends VerificationApiSupport {

    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VerificationFinalizeService finalizeService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static String visitParams() { return "{\"duration_min\":30,\"radius_m\":100}"; }

    private MvcResult sync(String token, List<Map<String, Object>> signals) throws Exception {
        MvcResult res = postJsonAuth("/api/v1/verifications/sync", token, syncBody(signals));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res;
    }

    /** 어제 귀속 상태(행이 없으면 null). */
    private String yesterdayStatusOf(UUID challengeMemberId) {
        List<String> rows = jdbc().queryForList(
                "SELECT status FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                String.class, bytes(challengeMemberId), java.sql.Date.valueOf(LocalDate.now(KST).minusDays(1)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String statusOn(UUID challengeMemberId, LocalDate date) {
        List<String> rows = jdbc().queryForList(
                "SELECT status FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                String.class, bytes(challengeMemberId), java.sql.Date.valueOf(date));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String failureReasonOn(UUID challengeMemberId, LocalDate date) {
        List<String> rows = jdbc().queryForList(
                "SELECT failureReason FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                String.class, bytes(challengeMemberId), java.sql.Date.valueOf(date));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 챌린지 시작일을 당겨 과거 날짜도 인증 대상이 되게 한다. */
    private void startedDaysAgo(UUID challengeId, int days) {
        jdbc().update("UPDATE challenges SET start_date = DATE_SUB(CURDATE(), INTERVAL ? DAY) WHERE id = ?",
                days, bytes(challengeId));
    }

    @Test
    @DisplayName("[P0] 귀속일에 sync 가 한 번도 없었어도, 다음 날 올라온 그날 신호가 인정된다")
    void lateSignalCountsEvenWhenTheDayHadNoSyncAtAll() throws Exception {
        Member me = member(uniq("grace-norow"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        startedDaysAgo(challenge, 3);

        // 어제는 앱을 아예 켜지 않아 판정 행이 없다.
        assertThat(yesterdayStatusOf(memberId)).isNull();

        // 오늘 첫 sync 에 어제 다녀온 기록이 올라온다(절전으로 밀렸던 구간).
        sync(me.token(), List.of(
                geofenceSignal(memberId, "ENTER", todayAt(9, 0).minusSeconds(86_400)),
                geofenceSignal(memberId, "EXIT", todayAt(10, 0).minusSeconds(86_400))));

        assertThat(yesterdayStatusOf(memberId))
                .as("붙일 행이 없다는 이유로 버려지면 유예 구간이 이름뿐이다")
                .isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("[P0] 하루 종일 신호가 없던 날도 확정 시각이 지나면 실패로 확정된다")
    void aDayWithNoSignalAtAllIsStillConfirmedAsFailure() throws Exception {
        Member me = member(uniq("grace-nosignal"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        startedDaysAgo(challenge, 5);

        LocalDate twoDaysAgo = LocalDate.now(KST).minusDays(2);
        assertThat(statusOn(memberId, twoDaysAgo)).as("행 자체가 없다").isNull();

        finalizeService.materializeDueTargets();
        finalizeService.finalizeDue();

        assertThat(statusOn(memberId, twoDaysAgo))
                .as("확정되지 않으면 통계에서 통째로 사라진다")
                .isEqualTo("FAILED");
        assertThat(failureReasonOn(memberId, twoDaysAgo)).isEqualTo("NO_SIGNAL_RECEIVED");
    }

    @Test
    @DisplayName("[P0] 무신호 확정은 아직 유예 중인 어제 건을 건드리지 않는다")
    void materializeDoesNotTouchYesterdayStillInGrace() throws Exception {
        Member me = member(uniq("grace-keep"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        startedDaysAgo(challenge, 5);

        finalizeService.materializeDueTargets();
        finalizeService.finalizeDue();

        assertThat(statusOn(memberId, LocalDate.now(KST).minusDays(1)))
                .as("어제는 아직 신호를 받는 중이다 — 확정 대상이 아니다")
                .isIn(null, "PENDING");
        assertThat(statusOn(memberId, LocalDate.now(KST)))
                .as("오늘은 더더욱 아니다")
                .isIn(null, "PENDING");
    }

    @Test
    @DisplayName("[P1] 유예일 재평가도 중복 제거를 거친 신호만 쓴다 — 재전송이 사용 시간을 부풀리지 않는다")
    void graceDayEvaluationUsesDedupedSignals() throws Exception {
        Member me = member(uniq("grace-dedup"));
        UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":60}");
        UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.ridi.books"));
        startedDaysAgo(challenge, 3);

        // 어제 40분 사용 — 목표 60분에는 못 미친다.
        Map<String, Object> yesterdayUsage = usageSignal("com.ridi.books",
                todayAt(20, 0).minusSeconds(86_400), todayAt(20, 40).minusSeconds(86_400));

        sync(me.token(), List.of(yesterdayUsage));
        assertThat(yesterdayStatusOf(memberId)).isIn(null, "PENDING");

        // 같은 구간이 재전송된다(오프라인 복구·FCM 기동 후 일괄 전송).
        sync(me.token(), List.of(yesterdayUsage));

        assertThat(yesterdayStatusOf(memberId))
                .as("40분을 두 번 세면 80분이 되어 목표를 넘겨버린다")
                .isIn(null, "PENDING");
    }
}
