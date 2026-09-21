package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.repository.NotificationRepository;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.verification.VerificationApiSupport;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationRequest;
import com.ruleup.ruleup_backend.verification.service.VerificationManualService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 판정 결과 고지가 <b>성공한 날에도</b> 쌓이는가.
 *
 * <p>확정 배치({@code finalizeDue})는 <b>미확정 건만</b> 집어가고, {@code finalizeOne} 은 초입에서
 * {@code isTerminal()} 로 되돌아간다. 그래서 sync 나 수동 체크로 <b>즉시 확정된 성공</b>은 확정
 * 배치를 영영 거치지 않는다 — 확정 배치에만 발행부를 두면 실패한 날에만 알림이 오고 성공한 날은
 * 알림함이 비는 비대칭이 생긴다. 그 비대칭을 여기서 잡는다.
 *
 * <p>멱등은 {@code verification_id} 가 맡는다. 같은 날을 여러 번 sync 해도 판정은 하나뿐이므로
 * 알림도 하나뿐이어야 한다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class VerificationResultNotificationIT extends VerificationApiSupport {

    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;
    private static final String VISIT_PARAMS = "{\"duration_min\":30,\"radius_m\":100}";

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired NotificationRepository notificationRepository;
    @Autowired VerificationManualService manualService;
    @Autowired com.ruleup.ruleup_backend.verification.service.FailExpectedNoticeJob failExpectedJob;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private List<Notification> resultsOf(UUID userId) {
        return notificationRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(n -> "VERIFICATION_RESULT".equals(n.getType()))
                .toList();
    }

    /** 30분 목표를 채운 체류 — 즉시 SUCCESS 로 확정된다. */
    private void syncFullVisit(Member me, UUID memberId) throws Exception {
        postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(
                geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                geofenceSignal(memberId, "EXIT", todayAt(10, 0)))));
    }

    // =====================================================================
    @Nested
    @DisplayName("즉시 확정된 성공")
    class ImmediateSuccess {

        @Test
        @DisplayName("sync 로 성공하면 판정 결과가 쌓인다 — 확정 배치가 집어가지 않는 경로다")
        void syncSuccessNotifies() throws Exception {
            Member me = member(uniq("vrs"));
            UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", VISIT_PARAMS);
            UUID memberId = insertReadyMember(challengeId, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            syncFullVisit(me, memberId);

            assertThat(todayStatusOf(memberId)).as("전제 — 즉시 성공 확정").isEqualTo("SUCCESS");
            assertThat(resultsOf(me.id())).singleElement().satisfies(n -> {
                assertThat(n.getChallengeId()).isEqualTo(challengeId);
                assertThat(n.getDeeplink()).contains(challengeId.toString());
            });
        }

        @Test
        @DisplayName("같은 날을 다시 sync 해도 두 번 쌓이지 않는다 — verification_id 가 멱등 키다")
        void syncSuccessIsIdempotent() throws Exception {
            Member me = member(uniq("vri"));
            UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", VISIT_PARAMS);
            UUID memberId = insertReadyMember(challengeId, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            syncFullVisit(me, memberId);
            syncFullVisit(me, memberId);

            assertThat(resultsOf(me.id())).hasSize(1);
        }

        @Test
        @DisplayName("수동 체크 성공도 고지한다 — 자동·수동 어느 쪽도 성공한 날이 비지 않는다")
        void manualSuccessNotifies() throws Exception {
            Member me = member(uniq("vrm"));
            UUID challengeId = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            insertActiveMembership(challengeId, me.id(), "OWNER");

            manualService.submit(me.id(), challengeId, new ManualVerificationRequest(null, null));

            assertThat(resultsOf(me.id())).singleElement().satisfies(n ->
                    assertThat(n.getChallengeId()).isEqualTo(challengeId));
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("성공 알림 딥링크 — QA NOTI-15")
    class SuccessDeeplink {

        @Test
        @DisplayName("성공 알림은 ruleup://challenge/{challengeId} 로 방 상세에 들어간다")
        void successGoesToChallengeDetail() throws Exception {
            Member me = member(uniq("vrd"));
            UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", VISIT_PARAMS);
            UUID memberId = insertReadyMember(challengeId, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            syncFullVisit(me, memberId);

            assertThat(resultsOf(me.id())).singleElement().satisfies(n ->
                    assertThat(n.getDeeplink()).isEqualTo("ruleup://challenge/" + challengeId));
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("실패 예정 — 이의제기가 필요해요 (QA NOTI-16)")
    class FailExpected {

        private List<Notification> failExpectedOf(UUID userId) {
            return notificationRepository.findByUserIdOrderByIdDesc(userId).stream()
                    .filter(n -> "VERIFICATION_FAIL_EXPECTED".equals(n.getType()))
                    .toList();
        }

        private UUID pendingYesterday(Member me, UUID challengeId, UUID memberId, String failureReason) {
            java.time.LocalDate yesterday = java.time.LocalDate.now(KST).minusDays(1);
            UUID id = UUID.randomUUID();
            jdbc().update("INSERT INTO VerificationDaily " +
                            "(id, challengeMemberId, challengeId, userId, targetDate, status, method, failureReason, " +
                            " finalizeAfter, appealClosesAt) VALUES (?, ?, ?, ?, ?, 'PENDING', 'GPS_PRESENCE', ?, ?, ?)",
                    bytes(id), bytes(memberId), bytes(challengeId), bytes(me.id()), yesterday, failureReason,
                    java.sql.Timestamp.from(com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines.finalizeAfter(yesterday)),
                    java.sql.Timestamp.from(com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines.appealClosesAt(yesterday)));
            return id;
        }

        @Test
        @DisplayName("귀속일이 끝났는데 목표 미달이면 자정 배치가 이의 제출 화면 딥링크로 알린다 — 다시 돌아도 한 번")
        void unmetGoalAfterMidnight() throws Exception {
            Member me = member(uniq("vfe"));
            UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", VISIT_PARAMS);
            UUID memberId = insertReadyMember(challengeId, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            java.time.LocalDate yesterday = java.time.LocalDate.now(KST).minusDays(1);
            pendingYesterday(me, challengeId, memberId, null);

            failExpectedJob.notifyFor(java.time.LocalDate.now(KST).minusDays(1));
            failExpectedJob.notifyFor(java.time.LocalDate.now(KST).minusDays(1));

            assertThat(failExpectedOf(me.id())).singleElement().satisfies(n -> {
                // 이의 버튼이 있는 화면은 캘린더 일자 상세다 — 이의 내역이 아니다.
                assertThat(n.getDeeplink()).isEqualTo("ruleup://me/calendar/" + yesterday);
                assertThat(n.getTitle()).isEqualTo("이의제기가 필요해요");
                assertThat(n.getChallengeId()).isEqualTo(challengeId);
            });
        }

        @Test
        @DisplayName("자정 배치 뒤 첫 sync 로 열린 어제 목표 미달도 즉시 알리고, 재전송해도 한 번만 알린다")
        void lateGraceDayAfterDailyScanNotifies() throws Exception {
            failExpectedJob.runIfNeeded();
            Member me = member(uniq("vfl"));
            UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", VISIT_PARAMS);
            UUID memberId = insertReadyMember(challengeId, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            java.time.LocalDate yesterday = java.time.LocalDate.now(KST).minusDays(1);
            jdbc().update("UPDATE challenges SET start_date=? WHERE id=?", yesterday, bytes(challengeId));

            var synced = postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of()));
            assertThat(synced.getResponse().getStatus()).isEqualTo(200);
            assertThat(failExpectedOf(me.id())).singleElement().satisfies(n ->
                    assertThat(n.getDeeplink()).isEqualTo("ruleup://me/calendar/" + yesterday));

            postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of()));
            failExpectedJob.notifyFor(yesterday);
            assertThat(failExpectedOf(me.id())).hasSize(1);
        }

        @Test
        @DisplayName("귀속일 중 목표 미달은 아직 진행중이므로 이의 필요 알림을 보내지 않는다")
        void unmetGoalDuringTargetDayDoesNotNotify() throws Exception {
            Member me = member(uniq("vft"));
            UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", VISIT_PARAMS);
            insertReadyMember(challengeId, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            var synced = postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of()));

            assertThat(synced.getResponse().getStatus()).isEqualTo(200);
            assertThat(failExpectedOf(me.id())).isEmpty();
        }

        @Test
        @DisplayName("규칙 지키기형은 위반 없이 하루가 끝나면 실패 예정이 아니다 — 알리지 않는다")
        void constraintWithoutViolationIsQuiet() throws Exception {
            Member me = member(uniq("vfc"));
            UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE",
                    "{\"duration_min\":30,\"radius_m\":100,\"gps_presence\":\"AVOID\"}");
            UUID memberId = insertReadyMember(challengeId, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "편의점"), null);
            pendingYesterday(me, challengeId, memberId, null);

            failExpectedJob.notifyFor(java.time.LocalDate.now(KST).minusDays(1));

            assertThat(failExpectedOf(me.id())).isEmpty();
        }

        @Test
        @DisplayName("장소 피하기 위반이 잡힌 sync 에서 곧바로 알린다 — 확정 알림과 따로 쌓인다")
        void violationDuringSync() throws Exception {
            Member me = member(uniq("vfv"));
            UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE",
                    "{\"duration_min\":30,\"radius_m\":100,\"gps_presence\":\"AVOID\"}");
            UUID memberId = insertReadyMember(challengeId, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "편의점"), null);

            postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(0, 5)),
                    geofenceSignal(memberId, "EXIT", todayAt(0, 45)))));

            assertThat(failExpectedOf(me.id())).singleElement().satisfies(n ->
                    assertThat(n.getDeeplink())
                            .isEqualTo("ruleup://me/calendar/" + java.time.LocalDate.now(KST)));
        }
    }
}
