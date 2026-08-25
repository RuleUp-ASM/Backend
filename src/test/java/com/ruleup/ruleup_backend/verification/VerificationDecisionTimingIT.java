package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.service.VerificationFinalizeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 판정 시간 규칙 (인증 정책 §2 · 테크스펙 §5-1).
 *
 * <p>규칙은 한 줄이다 — <b>성공은 조건 충족 즉시, 실패는 귀속일 이틀 뒤 00:00 KST 에만 확정한다.</b>
 * 귀속일이 끝나도 하루는 더 기다리며, 그 사이 도착한 신호도 발생 시각이 맞으면 그대로 인정한다.
 * 신호가 늦게 도착하는 기기가 흔해서, 귀속일 종료 즉시 확정하면 실제로 수행한 사람이 억울하게 실패한다.
 * 그 유예 하루가 유저가 "이대로면 실패"를 보고 이의를 내는 창이기도 하다.
 *
 * <p>확정 이후 도착한 신호는 저장만 하고 판정을 바꾸지 않는다 — 구제는 이의제기 한 경로뿐이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationDecisionTimingIT extends VerificationApiSupport {

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

    private MvcResult sync(String token, List<Map<String, Object>> signals) throws Exception {
        MvcResult res = postJsonAuth("/api/v1/verifications/sync", token, syncBody(signals));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res;
    }

    private String todayApiStatus(String token, UUID challengeId) throws Exception {
        MvcResult res = getAuth("/api/v1/challenges/" + challengeId + "/verifications/today", token);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return read(res, "$.data.status");
    }

    /**
     * 확정 배치를 지금 돌려도 잡히도록 finalizeAfter 를 과거로 당긴다(시간 여행 대용).
     * 시각 컬럼은 UTC 로 저장되므로 UTC_TIMESTAMP 로 쓴다 — JVM 기본 시간대(KST)로 쓰면 9시간 미래가 된다.
     */
    private void makeDue(UUID challengeMemberId) {
        jdbc().update("UPDATE VerificationDaily SET finalizeAfter = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE " +
                        "WHERE challengeMemberId = ? AND targetDate = ?",
                bytes(challengeMemberId), java.sql.Date.valueOf(LocalDate.now(KST)));
    }

    /** 저장된 시각 컬럼을 UTC 기준 Instant 로 읽는다. */
    private Instant instantColumn(String column, UUID challengeMemberId) {
        String raw = jdbc().queryForObject(
                "SELECT DATE_FORMAT(" + column + ", '%Y-%m-%dT%H:%i:%sZ') FROM VerificationDaily " +
                        "WHERE challengeMemberId = ? AND targetDate = ?",
                String.class, bytes(challengeMemberId), java.sql.Date.valueOf(LocalDate.now(KST)));
        return (raw != null) ? Instant.parse(raw) : null;
    }

    private Instant finalizeAfterOf(UUID challengeMemberId) {
        return instantColumn("finalizeAfter", challengeMemberId);
    }

    private String failureReasonOf(UUID challengeMemberId) {
        List<String> rows = jdbc().queryForList(
                "SELECT failureReason FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                String.class, bytes(challengeMemberId), java.sql.Date.valueOf(LocalDate.now(KST)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Instant appealClosesAtOf(UUID challengeMemberId) {
        return instantColumn("appealClosesAt", challengeMemberId);
    }

    private Instant shareableAtOf(UUID challengeMemberId) {
        return instantColumn("shareableAt", challengeMemberId);
    }

    private static String visitParams() { return "{\"duration_min\":30,\"radius_m\":100}"; }

    @Nested
    @DisplayName("귀속일 중")
    class DuringTargetDate {

        @Test
        @DisplayName("최대 사용 시간을 넘겨도 그날은 실패로 저장하지 않고 '실패 예정'으로만 보인다")
        void maxUsageBreachIsOnlyFailExpected() throws Exception {
            Member me = member(uniq("timing-max"));
            UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MAX", "USAGE", "{\"duration_min\":10}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.instagram.android"));

            // 10분 제한인데 40분 썼다 — 위반이 이미 확인됐다.
            sync(me.token(), List.of(usageSignal("com.instagram.android", todayAt(20, 0), todayAt(20, 40))));

            assertThat(todayStatusOf(memberId))
                    .as("귀속일 중에는 최종 실패로 저장하지 않는다")
                    .isEqualTo("PENDING");
            assertThat(failureReasonOf(memberId)).isEqualTo("USAGE_EXCEEDED");
            assertThat(appealClosesAtOf(memberId))
                    .as("이의는 확정 전에 받는다 — 행을 여는 시점에 기한이 서 있다")
                    .isEqualTo(LocalDate.now(KST).plusDays(2).atStartOfDay(KST).toInstant());
            assertThat(shareableAtOf(memberId)).as("확정 전에는 피드에 실리지 않는다").isNull();
            assertThat(todayApiStatus(me.token(), challenge)).isEqualTo("FAIL_EXPECTED");
        }

        @Test
        @DisplayName("도달형 목표에 못 미치면 진행중이다 — 실패도 실패 예정도 아니다")
        void unmetAchievementGoalIsInProgress() throws Exception {
            Member me = member(uniq("timing-under"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            // 30분 목표인데 10분만 머물렀다.
            sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(9, 10))));

            assertThat(todayStatusOf(memberId)).isEqualTo("PENDING");
            assertThat(failureReasonOf(memberId)).isNull();
            assertThat(todayApiStatus(me.token(), challenge))
                    .as("귀속일이 아직 안 끝났으니 채울 기회가 남아 있다")
                    .isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("성공 조건을 채우면 기다리지 않고 즉시 완료된다")
        void successConfirmsImmediately() throws Exception {
            Member me = member(uniq("timing-success"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(10, 0))));

            assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
            assertThat(todayApiStatus(me.token(), challenge)).isEqualTo("DONE");
            assertThat(shareableAtOf(memberId)).as("성공은 즉시 공유 가능").isNotNull();
        }

        @Test
        @DisplayName("실패 예정이 붙은 뒤라도 늦게 도착한 신호로 성공을 되찾을 수 있다")
        void lateSignalCanStillRescueBeforeConfirmation() throws Exception {
            Member me = member(uniq("timing-rescue"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(9, 10))));
            assertThat(todayStatusOf(memberId)).isEqualTo("PENDING");

            // 절전 때문에 늦게 올라온 나머지 구간.
            sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(10, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(10, 40))));

            assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("귀속일이 끝난 뒤에도 늦게 도착한 신호로 성공을 되찾을 수 있다 — 유예 하루")
        void lateSignalDuringGraceStillCounts() throws Exception {
            Member me = member(uniq("timing-grace"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            // 어제 귀속 건이 목표 미달로 남아 있다(귀속일은 끝났고 확정은 아직).
            UUID verificationId = UUID.randomUUID();
            jdbc().update("INSERT INTO VerificationDaily " +
                            "(id, challengeMemberId, challengeId, userId, targetDate, status, finalizeAfter, appealClosesAt) " +
                            "VALUES (?, ?, ?, ?, DATE_SUB(CURDATE(), INTERVAL 1 DAY), 'PENDING', " +
                            " UTC_TIMESTAMP(6) + INTERVAL 1 DAY, UTC_TIMESTAMP(6) + INTERVAL 1 DAY)",
                    bytes(verificationId), bytes(memberId), bytes(challenge), bytes(me.id()));

            // 절전 때문에 하루 늦게 올라온 어제 기록.
            sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0).minusSeconds(86_400)),
                    geofenceSignal(memberId, "EXIT", todayAt(10, 0).minusSeconds(86_400))));

            String status = jdbc().queryForObject(
                    "SELECT status FROM VerificationDaily WHERE id = ?", String.class, bytes(verificationId));
            assertThat(status)
                    .as("확정 전에 도착했고 발생 시각이 귀속일 조건에 맞으면 인정한다")
                    .isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("확정 시각은 판정 유형과 무관하게 귀속일 이틀 뒤 00:00 KST 다")
        void finalizeBoundaryIsIdenticalAcrossMethods() throws Exception {
            Member me = member(uniq("timing-boundary"));

            UUID gps = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID gpsMember = insertReadyMember(gps, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            UUID sleep = insertAutoChallenge(me.id(), "SLEEP", "SLEEP", "{\"sleep_hours\":7}");
            UUID sleepMember = insertReadyMember(sleep, me.id(), null, null);
            UUID screen = insertAutoChallenge(me.id(), "SCREEN_TIME_MAX", "USAGE", "{\"duration_min\":10}");
            UUID screenMember = insertReadyMember(screen, me.id(), null, screenApps("com.instagram.android"));

            sync(me.token(), List.of(geofenceSignal(gpsMember, "ENTER", todayAt(9, 0))));

            Instant expected = LocalDate.now(KST).plusDays(2).atStartOfDay(KST).toInstant();
            assertThat(finalizeAfterOf(gpsMember)).isEqualTo(expected);
            assertThat(finalizeAfterOf(sleepMember))
                    .as("수면도 별도 cutoff 없이 같은 경계를 쓴다")
                    .isEqualTo(expected);
            assertThat(finalizeAfterOf(screenMember)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("확정 배치")
    class Finalization {

        @Test
        @DisplayName("확정 시각이 지나면 위반이 남아 있는 건만 실패로 확정된다")
        void breachBecomesFailureAfterMidnight() throws Exception {
            Member me = member(uniq("final-breach"));
            UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MAX", "USAGE", "{\"duration_min\":10}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.instagram.android"));

            sync(me.token(), List.of(usageSignal("com.instagram.android", todayAt(20, 0), todayAt(20, 40))));
            makeDue(memberId);
            finalizeService.finalizeDue();

            assertThat(todayStatusOf(memberId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(memberId)).isEqualTo("USAGE_EXCEEDED");
            assertThat(shareableAtOf(memberId))
                    .as("이의는 확정 전에 이미 마감됐다 — 확정된 실패는 바로 공유된다")
                    .isNotNull();
        }

        @Test
        @DisplayName("규칙 지키기형에서 위반이 없으면 확정 시각에 완료가 된다")
        void constraintWithoutBreachBecomesSuccess() throws Exception {
            Member me = member(uniq("final-clean"));
            UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MAX", "USAGE", "{\"duration_min\":60}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.instagram.android"));

            sync(me.token(), List.of(usageSignal("com.instagram.android", todayAt(20, 0), todayAt(20, 10))));
            makeDue(memberId);
            finalizeService.finalizeDue();

            assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("도달형이 목표에 못 미친 채 확정 시각을 넘기면 실패가 된다")
        void unmetAchievementBecomesFailure() throws Exception {
            Member me = member(uniq("final-unmet"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(9, 10))));
            makeDue(memberId);
            finalizeService.finalizeDue();

            assertThat(todayStatusOf(memberId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(memberId)).isEqualTo("INSUFFICIENT_DWELL");
        }

        @Test
        @DisplayName("배치를 다시 돌려도 확정 결과가 바뀌거나 중복되지 않는다")
        void rerunIsIdempotent() throws Exception {
            Member me = member(uniq("final-rerun"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            sync(me.token(), List.of(geofenceSignal(memberId, "ENTER", todayAt(9, 0))));
            makeDue(memberId);
            finalizeService.finalizeDue();

            Instant firstConfirmed = instantColumn("verifiedAt", memberId);

            finalizeService.finalizeDue();
            finalizeService.finalizeDue();

            assertThat(todayStatusOf(memberId)).isEqualTo("FAILED");
            assertThat(jdbc().queryForObject(
                    "SELECT COUNT(*) FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                    Integer.class, bytes(memberId), java.sql.Date.valueOf(LocalDate.now(KST)))).isEqualTo(1);
            assertThat(instantColumn("verifiedAt", memberId))
                    .as("이미 확정된 건은 다시 확정하지 않는다")
                    .isEqualTo(firstConfirmed);
        }

        @Test
        @DisplayName("확정 이후 도착한 신호는 저장만 되고 판정을 바꾸지 않는다 — 절대 조건")
        void signalsArrivingAfterConfirmationNeverChangeTheResult() throws Exception {
            Member me = member(uniq("final-late"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            sync(me.token(), List.of(geofenceSignal(memberId, "ENTER", todayAt(9, 0))));
            makeDue(memberId);
            finalizeService.finalizeDue();
            assertThat(todayStatusOf(memberId)).isEqualTo("FAILED");

            // 뒤늦게 올라온 "사실은 2시간 있었다" 기록.
            sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(11, 0))));

            assertThat(todayStatusOf(memberId))
                    .as("확정 이후 도착분은 판정에 반영하지 않는다 — 구제는 이의제기로만")
                    .isEqualTo("FAILED");
        }
    }
}
