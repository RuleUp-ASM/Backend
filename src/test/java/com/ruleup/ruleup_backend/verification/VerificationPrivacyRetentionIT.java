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

        /** 파기 예정 시각을 과거로 돌린다 — 배치가 집어 갈 상태로 만든다. */
        private void makeDue(UUID userId) {
            jdbc().update("UPDATE verification_location_signals "
                    + "SET purgeAfter = DATE_SUB(NOW(6), INTERVAL 1 DAY) WHERE userId = ?", bytes(userId));
        }

        private int purgedRows(UUID userId) {
            Integer n = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM verification_location_signals "
                            + "WHERE userId = ? AND purgedAt IS NOT NULL", Integer.class, bytes(userId));
            return (n != null) ? n : 0;
        }

        @Test
        @DisplayName("[P1] 파기 타이머는 적재 시점에 귀속일 기준으로 걸린다")
        void purgeTimerIsStampedAtIngestFromTheTargetDate() throws Exception {
            Member me = member(uniq("privacy-stamp"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            sync(me.token(), List.of(geofenceSignal(memberId, "ENTER", todayAt(9, 0))));

            Integer withoutTimer = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM verification_location_signals "
                            + "WHERE userId = ? AND purgeAfter IS NULL", Integer.class, bytes(me.id()));
            assertThat(withoutTimer)
                    .as("확정 때만 걸면, 인증에 한 번도 쓰이지 않은 좌표는 타이머 없이 남아 "
                            + "파티션이 걷어갈 때까지 파기 기록조차 생기지 않는다")
                    .isZero();
        }

        @Test
        @DisplayName("[P1] 미확정 판정이 남아 있으면 시각이 지나도 좌표를 지우지 않는다")
        void coordinatesSurviveWhileAVerdictIsStillPending() throws Exception {
            Member me = member(uniq("privacy-pending"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            // 진입만 있어 목표 체류에 못 미친다 — 판정은 PENDING 으로 남는다.
            sync(me.token(), List.of(geofenceSignal(memberId, "ENTER", todayAt(9, 0))));
            assertThat(todayStatusOf(memberId)).isIn(null, "PENDING");
            makeDue(me.id());

            locationPurge.purgeDue();

            assertThat(purgedRows(me.id()))
                    .as("확정 배치가 밀린 사이에 좌표를 지우면 판정할 근거가 사라진다")
                    .isZero();
        }

        @Test
        @DisplayName("[P1] 한 챌린지가 먼저 성공해도 다른 챌린지의 좌표를 지우지 않는다")
        void anEarlySuccessDoesNotPurgeCoordinatesSharedWithAnotherChallenge() throws Exception {
            Member me = member(uniq("privacy-shared"));
            // 두 장소 챌린지가 같은 날짜의 <b>같은 위치 원본</b>을 공유한다(신호는 1회만 저장된다).
            UUID doneChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID doneMember = insertReadyMember(doneChallenge, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            UUID openChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID openMember = insertReadyMember(openChallenge, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 100, "도서관"), null);

            // 첫 챌린지는 체류를 채워 즉시 성공, 두 번째는 진입 신호가 없어 미확정으로 남는다.
            sync(me.token(), List.of(
                    geofenceSignal(doneMember, "ENTER", todayAt(9, 0)),
                    geofenceSignal(doneMember, "EXIT", todayAt(10, 0))));
            assertThat(todayStatusOf(doneMember)).isEqualTo("SUCCESS");
            assertThat(todayStatusOf(openMember)).isIn(null, "PENDING");

            makeDue(me.id());
            locationPurge.purgeDue();

            assertThat(purgedRows(me.id()))
                    .as("먼저 확정한 챌린지 기준으로 타이머를 잡으면, 아침에 성공한 하나가 "
                            + "그날 좌표 전부를 일찍 지워 D+2 에 확정될 다른 챌린지가 근거를 잃는다")
                    .isZero();
        }

        @Test
        @DisplayName("[P1] 판정 행이 아직 열리지 않았으면 「확정됐다」로 읽지 않는다")
        void coordinatesWaitWhileTheVerdictRowHasNotBeenOpenedYet() throws Exception {
            Member me = member(uniq("privacy-lazy"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(10, 0))));
            // 채우기 배치가 늦어 판정 행이 아직 없는 상태를 만든다.
            jdbc().update("DELETE FROM VerificationMethodResult WHERE verificationDailyId IN "
                    + "(SELECT id FROM VerificationDaily WHERE challengeMemberId = ?)", bytes(memberId));
            jdbc().update("DELETE FROM VerificationDaily WHERE challengeMemberId = ?", bytes(memberId));
            makeDue(me.id());

            locationPurge.purgeDue();

            assertThat(purgedRows(me.id()))
                    .as("행이 없으면 「PENDING 이 없다」가 「확정됐다」로 잘못 읽힌다")
                    .isZero();
        }

        @Test
        @DisplayName("[P1] 모두 확정된 뒤에는 좌표가 지워지고, 판정 기록은 남는다")
        void coordinatesArePurgedOnceEveryVerdictIsSettled() throws Exception {
            Member me = member(uniq("privacy-purged"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            sync(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(10, 0))));
            assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
            makeDue(me.id());

            locationPurge.purgeDue();

            assertThat(purgedRows(me.id())).isGreaterThanOrEqualTo(1);
            assertThat(countIn("verification_location_signals", me.id()))
                    .as("행까지 지우면 「이 신호를 받아 이렇게 판정했다」를 설명할 수 없다")
                    .isEqualTo(purgedRows(me.id()));
            assertThat(todayStatusOf(memberId)).as("판정 결과는 그대로다").isEqualTo("SUCCESS");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("원본 파티션과 파기 타이머의 경계")
    class PartitionHold {

        @Test
        @DisplayName("[P1] 파기 시각이 지났어도 미확정이면 파티션 파기 판단이 붙잡는다")
        void aPendingVerdictStillHoldsThePartitionAfterTheTimerPasses() throws Exception {
            Member me = member(uniq("privacy-hold"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            // 진입만 있어 판정은 PENDING 으로 남고, 파기 시각은 이미 지난 상태로 만든다.
            sync(me.token(), List.of(geofenceSignal(memberId, "ENTER", todayAt(9, 0))));
            assertThat(todayStatusOf(memberId)).isIn(null, "PENDING");
            jdbc().update("UPDATE verification_location_signals "
                    + "SET purgeAfter = DATE_SUB(NOW(6), INTERVAL 1 DAY) WHERE userId = ?", bytes(me.id()));

            java.time.LocalDate today = java.time.LocalDate.now(KST);
            assertThat(partitionMaintainer.countUnconfirmed(today))
                    .as("「파기 시각이 아직 안 왔는가」로 물으면 경계가 지난 뒤에는 늘 0 이라, "
                            + "확정 전 좌표가 파티션째 사라진다")
                    .isGreaterThanOrEqualTo(1);
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
