package com.ruleup.ruleup_backend.verification;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 과거 날짜는 <b>그 날 적용되던 설정</b>으로 평가한다 (백엔드 테크스펙 §4-1 "당시 적용 설정을 사용한 과거 재판정").
 *
 * <p>확정이 귀속일 이틀 뒤로 밀리면서 과거 날짜를 다시 평가하는 창이 하루 생겼다. 그런데 앵커와 대상 앱은
 * 현재 값만 들고 있어서, 유예 구간에 장소를 바꾸면 <b>어제 판정이 새 장소 기준으로 돌아간다</b> —
 * 어제 갔던 곳이 갑자기 "안 간 곳"이 되거나, 그 반대가 된다.
 *
 * <p>설정 변경은 월 1회라 자주 일어나지 않지만, 일어나면 그 사람의 판정이 조용히 뒤집힌다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationSettingHistoryIT extends VerificationApiSupport {

    /** 두 장소는 약 4km 떨어져 서로의 반경 밖이다. */
    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;
    private static final double LIBRARY_LAT = 37.5326, LIBRARY_LNG = 127.0246;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

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

    private void startedDaysAgo(UUID challengeId, int days) {
        jdbc().update("UPDATE challenges SET start_date = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY) WHERE id = ?",
                days, bytes(challengeId));
    }

    private String statusOn(UUID challengeMemberId, LocalDate date) {
        List<String> rows = jdbc().queryForList(
                "SELECT status FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                String.class, bytes(challengeMemberId), java.sql.Date.valueOf(date));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 어제부터 적용되는 설정으로 바꾼 것처럼 이력을 심는다(오늘 변경 = 오늘부터 적용). */
    private void changeAnchorsToday(UUID challengeMemberId, String anchorsJson) {
        jdbc().update("UPDATE challenge_members SET anchors = ? WHERE id = ?",
                anchorsJson, bytes(challengeMemberId));
        jdbc().update("INSERT INTO verification_setting_snapshots " +
                        "(id, challengeMemberId, kind, effectiveFrom, payload) " +
                        "VALUES (?, ?, 'ANCHORS', DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), ?)",
                bytes(UUID.randomUUID()), bytes(challengeMemberId), anchorsJson);
    }

    private void seedAnchorHistory(UUID challengeMemberId, int daysAgo, String anchorsJson) {
        jdbc().update("INSERT INTO verification_setting_snapshots " +
                        "(id, challengeMemberId, kind, effectiveFrom, payload) " +
                        "VALUES (?, ?, 'ANCHORS', DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY), ?)",
                bytes(UUID.randomUUID()), bytes(challengeMemberId), daysAgo, anchorsJson);
    }

    @Test
    @DisplayName("유예 구간에 장소를 바꿔도 어제 판정은 어제 장소로 평가된다")
    void yesterdayIsEvaluatedWithYesterdaysAnchor() throws Exception {
        Member me = member(uniq("hist-anchor"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 200, "헬스장"), null);
        startedDaysAgo(challenge, 5);
        seedAnchorHistory(memberId, 5, anchor(GYM_LAT, GYM_LNG, 200, "헬스장"));

        // 오늘 장소를 도서관으로 바꿨다(월 1회 변경).
        changeAnchorsToday(memberId, anchor(LIBRARY_LAT, LIBRARY_LNG, 200, "도서관"));

        // 어제 헬스장에 40분 있었던 측위 기록이 이제야 올라온다.
        List<java.time.Instant> times = List.of(
                todayAt(9, 0).minusSeconds(86_400), todayAt(9, 10).minusSeconds(86_400),
                todayAt(9, 20).minusSeconds(86_400), todayAt(9, 30).minusSeconds(86_400),
                todayAt(9, 40).minusSeconds(86_400));
        sync(me.token(), List.of(locationSignal(GYM_LAT, GYM_LNG, times)));

        assertThat(statusOn(memberId, LocalDate.now(KST).minusDays(1)))
                .as("어제는 헬스장이 인증 장소였다 — 오늘 바꾼 도서관으로 판정하면 안 된다")
                .isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("오늘 판정은 오늘 설정으로 평가된다")
    void todayUsesTodaysAnchor() throws Exception {
        Member me = member(uniq("hist-today"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 200, "헬스장"), null);
        startedDaysAgo(challenge, 5);
        seedAnchorHistory(memberId, 5, anchor(GYM_LAT, GYM_LNG, 200, "헬스장"));
        changeAnchorsToday(memberId, anchor(LIBRARY_LAT, LIBRARY_LNG, 200, "도서관"));

        // 오늘 도서관에 40분 머물렀다.
        List<java.time.Instant> times = List.of(
                todayAt(14, 0), todayAt(14, 10), todayAt(14, 20), todayAt(14, 30), todayAt(14, 40));
        sync(me.token(), List.of(locationSignal(LIBRARY_LAT, LIBRARY_LNG, times)));

        assertThat(statusOn(memberId, LocalDate.now(KST))).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("이력이 없는 기존 멤버는 현재 설정으로 평가된다 — 이력 도입 전 데이터가 깨지지 않는다")
    void membersWithoutHistoryFallBackToCurrentSettings() throws Exception {
        Member me = member(uniq("hist-none"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 200, "헬스장"), null);
        startedDaysAgo(challenge, 5);
        // 스냅샷을 심지 않는다.

        List<java.time.Instant> times = List.of(
                todayAt(9, 0), todayAt(9, 10), todayAt(9, 20), todayAt(9, 30), todayAt(9, 40));
        sync(me.token(), List.of(locationSignal(GYM_LAT, GYM_LNG, times)));

        assertThat(statusOn(memberId, LocalDate.now(KST))).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("설정 API 로 장소를 바꾸면 이력이 함께 남는다")
    void changingAnchorsThroughTheApiRecordsHistory() throws Exception {
        Member me = member(uniq("hist-api"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), null, null);
        jdbc().update("UPDATE challenge_members SET setup_status = 'PENDING_SETUP' WHERE id = ?", bytes(memberId));

        Map<String, Object> body = Map.of("location", Map.of("anchors",
                List.of(Map.of("lat", GYM_LAT, "lng", GYM_LNG, "label", "헬스장"))));
        MvcResult res = postJsonAuth("/api/v1/challenges/" + challenge + "/setup", me.token(), body);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        Integer snapshots = jdbc().queryForObject(
                "SELECT COUNT(*) FROM verification_setting_snapshots WHERE challengeMemberId = ? AND kind = 'ANCHORS'",
                Integer.class, bytes(memberId));
        assertThat(snapshots).as("최초 셋업도 그 시점부터 적용되는 설정이라 이력이 남아야 한다").isEqualTo(1);
    }

    // ===== 인증 시간 중 장소 변경 — 막지 않고 내일부터 적용 =====

    private MvcResult putLocation(String token, UUID challenge, double lat, double lng, String label) throws Exception {
        Map<String, Object> body = Map.of("anchors", List.of(Map.of("lat", lat, "lng", lng, "label", label)));
        return mvc.perform(put("/api/v1/challenges/" + challenge + "/my-location")
                .header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    /** 오늘 판정 행을 만들고 인증 윈도우가 아직 열려 있게 둔다. */
    private void openTodaysWindow(String token, UUID challengeMemberId) throws Exception {
        sync(token, List.of());
        int updated = jdbc().update("UPDATE VerificationDaily SET windowClosesAt = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) " +
                        "WHERE challengeMemberId = ? AND targetDate = DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00'))",
                bytes(challengeMemberId));
        assertThat(updated).as("빈 sync 로도 오늘 판정 행이 생긴다").isEqualTo(1);
    }

    @Test
    @DisplayName("인증 시간 중에도 장소를 바꿀 수 있고, 새 장소는 내일부터 — 오늘은 이전 장소로 판정한다")
    void changingAnchorsInWindowAppliesFromTomorrow() throws Exception {
        Member me = member(uniq("hist-window"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        // 이력 없이 들어온 기존 멤버 — 새 장소를 멤버에 먼저 써도 오늘 판정이 새 장소로 폴백하면 안 된다.
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 200, "헬스장"), null);
        startedDaysAgo(challenge, 5);
        openTodaysWindow(me.token(), memberId);

        MvcResult res = putLocation(me.token(), challenge, LIBRARY_LAT, LIBRARY_LNG, "도서관");
        assertThat(res.getResponse().getStatus()).as("윈도우 중이라고 거부하지 않는다").isEqualTo(200);
        String tomorrow = LocalDate.now(KST).plusDays(1).atStartOfDay(KST)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        assertThat((String) read(res, "$.data.appliedFrom")).isEqualTo(tomorrow);
        assertThat((String) read(getAuth("/api/v1/challenges/" + challenge + "/my-location", me.token()),
                "$.data.appliedFrom")).as("조회도 저장 시각이 아니라 적용 시각을 내린다").isEqualTo(tomorrow);

        // 오늘 헬스장에 40분 — 오늘은 아직 헬스장이 인증 장소다.
        java.time.Instant now = java.time.Instant.now();
        List<java.time.Instant> times = List.of(now.minusSeconds(2_400), now.minusSeconds(1_800),
                now.minusSeconds(1_200), now.minusSeconds(600), now.minusSeconds(5));
        sync(me.token(), List.of(locationSignal(GYM_LAT, GYM_LNG, times)));

        assertThat(statusOn(memberId, LocalDate.now(KST))).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("인증 시간 밖이면 장소 변경은 바로 적용된다")
    void changingAnchorsOutsideWindowAppliesImmediately() throws Exception {
        Member me = member(uniq("hist-now"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 200, "헬스장"), null);

        MvcResult res = putLocation(me.token(), challenge, LIBRARY_LAT, LIBRARY_LNG, "도서관");
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) read(res, "$.data.appliedFrom")).isEqualTo("IMMEDIATE");
    }
}
