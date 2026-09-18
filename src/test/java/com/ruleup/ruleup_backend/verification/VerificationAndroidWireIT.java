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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Android 앱이 <b>실제로 보내는 형식</b>의 위치 신호가 판정에 들어가는지 검증한다(QA SIG-19).
 *
 * <p>다른 인증 테스트는 서버 형식({@code geofenceId}·ISO {@code at})으로 신호를 넣는다. 그래서 앱이
 * {@code anchorId}·epoch millis {@code observedAt} 을 보내 전환이 전부 null 로 저장되던 문제를 잡지 못했다.
 * 여기서는 stg 로그에 남은 실기기 본문과 같은 모양을 그대로 보낸다.
 * <ul>
 *   <li>지오펜스 id: 등록 때 붙인 requestId {@code "{userId}#{challengeId}#{index}"}</li>
 *   <li>시각: 전부 epoch millis 숫자, 신호 단위 {@code observedAt} 없음</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationAndroidWireIT extends VerificationApiSupport {

    private static final double CAFE_LAT = 37.4979, CAFE_LNG = 127.0276;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private void sync(String token, List<Map<String, Object>> signals) throws Exception {
        assertThat(postJsonAuth("/api/v1/verifications/sync", token, syncBody(signals))
                .getResponse().getStatus()).as("sync 응답").isEqualTo(200);
    }

    /** 앱의 GEOFENCE 신호 — {@code SyncRequest.kt} 의 GeofenceEventRequest 그대로. */
    private static Map<String, Object> androidGeofence(String anchorId, String transition, Instant at) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("anchorId", anchorId);
        event.put("transition", transition);
        event.put("observedAt", at.toEpochMilli());
        event.put("observedElapsedMillis", 1_000_000L);
        event.put("accuracy", 12.0);
        event.put("isMock", false);
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("type", "GEOFENCE");
        signal.put("events", List.of(event));
        return signal;
    }

    /** 앱의 LOCATION 신호 — 좌표 시각도 epoch millis 다. */
    private static Map<String, Object> androidLocation(double lat, double lng, List<Instant> times) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (Instant at : times) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("lat", lat);
            p.put("lng", lng);
            p.put("accuracy", 10.0);
            p.put("isMock", false);
            p.put("at", at.toEpochMilli());
            points.add(p);
        }
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("type", "LOCATION");
        signal.put("points", points);
        return signal;
    }

    private static String anchorId(UUID userId, UUID challengeId) {
        return userId + "#" + challengeId + "#0";
    }

    private String failureReasonOf(UUID memberId) {
        return jdbc().query("SELECT failureReason FROM VerificationDaily WHERE challengeMemberId = ? " +
                        "AND targetDate = DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00'))",
                rs -> rs.next() ? rs.getString(1) : null, bytes(memberId));
    }

    @Test
    @DisplayName("장소 방문 — 앱 형식 ENTER/EXIT 로 체류가 쌓여 인증된다")
    void visitWithAndroidGeofence() throws Exception {
        Member me = member(uniq("wire-visit"));
        UUID ch = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", "{\"duration_min\":30,\"radius_m\":100}");
        UUID memberId = insertReadyMember(ch, me.id(), anchor(CAFE_LAT, CAFE_LNG, 100, "스터디카페"), null);

        sync(me.token(), List.of(
                androidGeofence(anchorId(me.id(), ch), "ENTER", todayAt(13, 0)),
                androidGeofence(anchorId(me.id(), ch), "EXIT", todayAt(14, 0))));

        assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
        assertThat(dwellMinutesOf(memberId)).isGreaterThanOrEqualTo(30L);
    }

    @Test
    @DisplayName("장소 피하기 — 앱 형식 ENTER 가 위반으로 잡힌다(거짓 통과 없음)")
    void avoidWithAndroidGeofence() throws Exception {
        Member me = member(uniq("wire-avoid"));
        UUID ch = insertAutoChallenge(me.id(), "GPS_AVOID", "GEOFENCE", "{\"duration_min\":0,\"radius_m\":100}");
        UUID memberId = insertReadyMember(ch, me.id(), anchor(CAFE_LAT, CAFE_LNG, 100, "편의점"), null);

        sync(me.token(), List.of(androidGeofence(anchorId(me.id(), ch), "ENTER", todayAt(9, 0))));

        assertThat(failureReasonOf(memberId)).isEqualTo("ENTERED_AVOID_ZONE");
    }

    @Test
    @DisplayName("다른 계정이 등록한 지오펜스 id 는 같은 방이어도 내 전환으로 세지 않는다")
    void anchorOfAnotherUserIsIgnored() throws Exception {
        Member me = member(uniq("wire-other"));
        UUID ch = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", "{\"duration_min\":30,\"radius_m\":100}");
        UUID memberId = insertReadyMember(ch, me.id(), anchor(CAFE_LAT, CAFE_LNG, 100, "스터디카페"), null);
        UUID previousAccount = UUID.randomUUID();   // 같은 기기에 남아 있던 예전 계정의 지오펜스

        sync(me.token(), List.of(
                androidGeofence(anchorId(previousAccount, ch), "ENTER", todayAt(13, 0)),
                androidGeofence(anchorId(previousAccount, ch), "EXIT", todayAt(14, 0))));

        assertThat(todayStatusOf(memberId)).isIn(null, "PENDING");
    }

    @Test
    @DisplayName("건강 — 앱 형식(신호 단위 metric·평평한 출처·epoch millis) 걸음 수로 인증된다")
    void healthWithAndroidShape() throws Exception {
        Member me = member(uniq("wire-health"));
        UUID ch = insertAutoChallenge(me.id(), "HEALTH", "HC_RECORD", "{\"metric\":\"STEPS\",\"steps\":8000}");
        UUID memberId = insertReadyMember(ch, me.id(), null, null);

        Map<String, Object> reading = new LinkedHashMap<>();
        reading.put("recordId", "hc-1");
        reading.put("value", 8200.0);
        reading.put("startTime", todayAt(9, 0).toEpochMilli());
        reading.put("endTime", todayAt(10, 0).toEpochMilli());
        reading.put("recordingMethod", "AUTO");
        reading.put("originPackage", "com.sec.android.app.shealth");
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("type", "HEALTH");
        signal.put("date", java.time.LocalDate.now(KST).toString());
        signal.put("metric", "STEPS");
        signal.put("readings", List.of(reading));

        sync(me.token(), List.of(signal));

        assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("기상 — 앱 형식 firstUnlock(epoch millis)으로 인증된다")
    void wakeWithAndroidShape() throws Exception {
        Member me = member(uniq("wire-wake"));
        UUID ch = insertAutoChallenge(me.id(), "WAKE", "USAGE", "{\"target_time\":\"07:00\"}");
        UUID memberId = insertReadyMember(ch, me.id(), null, null);

        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("type", "WAKE");
        signal.put("firstUnlock", todayAt(6, 30).toEpochMilli());
        signal.put("firstScreenOn", todayAt(6, 28).toEpochMilli());
        signal.put("deviceSecure", true);

        sync(me.token(), List.of(signal));

        assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("측위 fallback — 앱 형식(epoch millis) 좌표로 체류가 쌓여 인증된다")
    void locationFallbackWithEpochMillis() throws Exception {
        Member me = member(uniq("wire-loc"));
        UUID ch = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", "{\"duration_min\":30,\"radius_m\":200}");
        UUID memberId = insertReadyMember(ch, me.id(), anchor(CAFE_LAT, CAFE_LNG, 200, "스터디카페"), null);

        sync(me.token(), List.of(androidLocation(CAFE_LAT, CAFE_LNG, List.of(
                todayAt(9, 0), todayAt(9, 5), todayAt(9, 10), todayAt(9, 15),
                todayAt(9, 20), todayAt(9, 25), todayAt(9, 30), todayAt(9, 35), todayAt(9, 40)))));

        assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
    }
}
