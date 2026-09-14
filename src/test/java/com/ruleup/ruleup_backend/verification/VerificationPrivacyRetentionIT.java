package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.service.LocationPurgeService;
import com.ruleup.ruleup_backend.verification.service.SignalPartitionMaintainer;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 개인정보 동의·보관·파기가 스펙대로 도는지 (인증 공통 5-6 · 백엔드 정합화 1·3절).
 *
 * <p>세 가지가 서로 다른 층이다.
 * <ul>
 *   <li><b>동의 게이트</b> — 법적 근거가 없으면 <b>받아 두면 안 된다</b>. 신호 위생처럼
 *       "받아서 안 쓴다"가 아니라 적재 이전에 떨어뜨린다.</li>
 *   <li><b>GPS 파기</b> — 건별 확정 시각 + 보관 기간. 고정 일괄 시각으로 잡으면 확정 전 건까지 지워진다.</li>
 *   <li><b>이상탐지 입력</b> — 성공 인증에서 뽑은 feature 만 별도 도메인에 남긴다. 원본을 복사하지 않는다.</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationPrivacyRetentionIT extends VerificationApiSupport {

    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired LocationPurgeService locationPurge;
    @Autowired SignalPartitionMaintainer partitionMaintainer;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static String visitParams() { return "{\"duration_min\":30,\"radius_m\":100}"; }

    private MvcResult sync(String token, List<Map<String, Object>> signals) throws Exception {
        return postJsonAuth("/api/v1/verifications/sync", token, syncBody(signals));
    }

    private void revokeConsents(UUID userId) {
        jdbc().update("UPDATE user_agreement_states SET agreed = 0 "
                + "WHERE user_id = ? AND agreement_type IN ('LOCATION_INFO','HEALTH_INFO')", bytes(userId));
    }

    private int countIn(String table, UUID userId) {
        Integer n = jdbc().queryForObject("SELECT COUNT(*) FROM " + table + " WHERE userId = ?",
                Integer.class, bytes(userId));
        return (n != null) ? n : 0;
    }

    // =====================================================================
    @Nested
    @DisplayName("개별 동의 게이트")
    class ConsentGate {

        @Test
        @DisplayName("[P1] 동의가 없으면 위치 원본을 저장하지 않고, 필요한 동의를 회신한다")
        void locationSignalsAreRejectedWithoutConsent() throws Exception {
            Member me = member(uniq("privacy-consent"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            revokeConsents(me.id());

            MvcResult res = sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(10, 0))));

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat(res.getResponse().getContentAsString())
                    .as("클라가 동의 화면으로 보낼 근거가 있어야 한다")
                    .contains("LOCATION_INFO");
            assertThat(countIn("verification_location_signals", me.id()))
                    .as("법적 근거 없이 받아 두면 안 된다 — 배제 표시가 아니라 미적재다")
                    .isZero();
        }

        @Test
        @DisplayName("[P1] 동의가 필요 없는 신호는 같은 요청에서 계속 처리된다")
        void unrelatedSignalsSurviveTheGate() throws Exception {
            Member me = member(uniq("privacy-partial"));
            UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":30}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.ridi.books"));
            revokeConsents(me.id());

            assertThat(sync(me.token(), List.of(
                    usageSignal("com.ridi.books", todayAt(9, 0), todayAt(10, 0))))
                    .getResponse().getStatus()).isEqualTo(200);

            assertThat(todayStatusOf(memberId))
                    .as("위치 동의가 없다고 앱 사용 인증까지 멈추면 안 된다")
                    .isEqualTo("SUCCESS");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("GPS 원본 파기")
    class GpsPurge {

        @Test
        @DisplayName("[P1] 확정된 판정의 좌표에만 파기 타이머가 걸린다")
        void purgeTimerStartsAtConfirmation() throws Exception {
            Member me = member(uniq("privacy-purge"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            // 아직 목표 체류에 못 미친다 — 확정되지 않았으므로 타이머도 걸리지 않아야 한다.
            sync(me.token(), List.of(geofenceSignal(memberId, "ENTER", todayAt(9, 0))));
            Integer scheduled = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM verification_location_signals WHERE userId = ? AND purgeAfter IS NOT NULL",
                    Integer.class, bytes(me.id()));
            assertThat(scheduled).as("확정 전 좌표를 지울 예정으로 잡으면 판정 근거가 먼저 사라진다").isZero();

            // 체류가 채워져 즉시 완료 확정 — 이제 목적이 달성됐다.
            sync(me.token(), List.of(geofenceSignal(memberId, "EXIT", todayAt(10, 0))));
            assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");

            Integer afterConfirm = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM verification_location_signals WHERE userId = ? AND purgeAfter IS NOT NULL",
                    Integer.class, bytes(me.id()));
            assertThat(afterConfirm).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("[P1] 파기 시각이 지난 좌표는 지워지고, 판정 기록은 남는다")
        void expiredCoordinatesArePurgedButTheRowRemains() throws Exception {
            Member me = member(uniq("privacy-purged"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(10, 0))));
            // 보관 기간이 이미 지난 것으로 돌린다.
            jdbc().update("UPDATE verification_location_signals SET purgeAfter = DATE_SUB(NOW(6), INTERVAL 1 DAY) "
                    + "WHERE userId = ?", bytes(me.id()));

            locationPurge.purgeDue();

            Integer purged = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM verification_location_signals WHERE userId = ? AND purgedAt IS NOT NULL",
                    Integer.class, bytes(me.id()));
            assertThat(purged).isGreaterThanOrEqualTo(1);
            assertThat(countIn("verification_location_signals", me.id()))
                    .as("행까지 지우면 「이 신호를 받아 이렇게 판정했다」를 설명할 수 없다")
                    .isEqualTo(purged);
            assertThat(todayStatusOf(memberId)).as("판정 결과는 그대로다").isEqualTo("SUCCESS");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("원본 파티션과 파기 타이머의 경계")
    class PartitionHold {

        @Test
        @DisplayName("[P1] 확정되지 않은 좌표는 파티션 파기 판단에서 「남아 있다」로 세어진다")
        void unconfirmedCoordinatesAreCountedBeforeDroppingThePartition() throws Exception {
            Member me = member(uniq("privacy-hold"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            // 진입만 있고 목표 체류에 못 미친다 — 확정되지 않았으므로 파기 타이머도 없다.
            sync(me.token(), List.of(geofenceSignal(memberId, "ENTER", todayAt(9, 0))));

            java.time.LocalDate today = java.time.LocalDate.now(KST);
            assertThat(partitionMaintainer.countUnconfirmed(today))
                    .as("시각만 보고 떨어뜨리면 판정도 못 한 좌표를 파기 기록 없이 잃는다")
                    .isGreaterThanOrEqualTo(1);

            // 체류가 채워져 확정되면 타이머가 걸리고, 그 뒤에는 붙잡을 이유가 없다.
            sync(me.token(), List.of(geofenceSignal(memberId, "EXIT", todayAt(10, 0))));
            assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");

            Integer stillUnconfirmed = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM verification_location_signals "
                            + "WHERE userId = ? AND purgeAfter IS NULL", Integer.class, bytes(me.id()));
            assertThat(stillUnconfirmed)
                    .as("확정됐는데도 타이머가 없으면 그 좌표는 영영 파기 대상이 되지 않는다")
                    .isZero();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("이상탐지 입력")
    class AnomalyEvents {

        @Test
        @DisplayName("[P1] 성공 인증에서 뽑은 feature 가 유형별 anomaly 도메인에 남는다")
        void successFeatureIsRecorded() throws Exception {
            Member me = member(uniq("privacy-anomaly"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(10, 0))));
            assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");

            Integer features = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM anomaly_location_events WHERE userId = ? AND eventType = 'FEATURE'",
                    Integer.class, bytes(me.id()));
            assertThat(features)
                    .as("원본이 사흘 만에 사라지므로 탐지가 읽을 창이 따로 있어야 한다")
                    .isEqualTo(1);

            String payload = jdbc().queryForObject(
                    "SELECT features FROM anomaly_location_events WHERE userId = ? AND eventType = 'FEATURE'",
                    String.class, bytes(me.id()));
            assertThat(payload)
                    .as("좌표 원본은 넣지 않는다 — GPS 조기 파기가 30일 보관보다 우선한다")
                    .doesNotContain("lat").doesNotContain("lng")
                    .contains("dwellMinutes");
        }
    }
}
