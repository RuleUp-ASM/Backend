package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 신호 API 계약이 스펙대로 구현돼 있는지 (인증 공통 1절 입력 타입 5종 · 백엔드 4-3 · 4-4).
 *
 * <p>여기 모인 것들은 전부 "계약에는 있는데 서버가 안 읽던" 항목이다. 없으면 조용히 틀린 판정이
 * 나간다 — 기상만 보내는 기기는 영영 기상이 안 잡히고, 강제 종료된 앱은 하루 종일 쓴 것으로
 * 계산되고, 예전 기기에 남은 백로그가 새 기기의 인증을 통과시킨다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationSignalContractIT extends VerificationApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private MvcResult sync(String token, Map<String, Object> body) throws Exception {
        return postJsonAuth("/api/v1/verifications/sync", token, body);
    }

    private void syncOk(String token, List<Map<String, Object>> signals) throws Exception {
        assertThat(sync(token, syncBody(signals)).getResponse().getStatus()).isEqualTo(200);
    }

    private String statusToday(UUID challengeMemberId) {
        return todayStatusOf(challengeMemberId);
    }

    // ===== 신호 조립 =====

    /** 기상 전용 입력 타입 — 앱 사용 이벤트를 싣지 않는 기기가 보낸다. */
    private static Map<String, Object> wakeSignal(String event, Instant at) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event", event);
        e.put("at", at.toString());
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("type", "WAKE");
        signal.put("observedAt", at.toString());
        signal.put("screenEvents", List.of(e));
        return signal;
    }

    private static Map<String, Object> usageEvent(String packageName, String type, Instant at) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("packageName", packageName);
        event.put("type", type);
        event.put("at", at.toString());
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("type", "SCREEN_TIME");
        signal.put("observedAt", at.toString());
        signal.put("appEvents", List.of(event));
        return signal;
    }

    /** Health Connect 레코드 — 구간과 레코드 식별자를 갖춘 형태. */
    private static Map<String, Object> healthSignal(String recordId, double value,
                                                    Instant from, Instant to) {
        Map<String, Object> reading = new LinkedHashMap<>();
        reading.put("recordId", recordId);
        reading.put("metric", "STEPS");
        reading.put("value", value);
        reading.put("unit", "count");
        reading.put("startTime", from.toString());
        reading.put("endTime", to.toString());
        reading.put("origin", Map.of("dataOrigin", "com.sec.android.app.shealth",
                "recordingMethod", "AUTO", "device", "WATCH"));
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("type", "HEALTH");
        signal.put("recordId", recordId);
        signal.put("observedAt", to.toString());
        signal.put("readings", List.of(reading));
        return signal;
    }

    // =====================================================================
    @Nested
    @DisplayName("입력 타입 5종")
    class InputTypes {

        @Test
        @DisplayName("[P1] WAKE 로 올라온 잠금해제가 기상 판정에 쓰인다")
        void wakeSignalDrivesWakeVerification() throws Exception {
            Member me = member(uniq("contract-wake"));
            UUID challenge = insertAutoChallenge(me.id(), "WAKE", "USAGE", "{\"target_time\":\"07:00\"}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, null);

            syncOk(me.token(), List.of(wakeSignal("UNLOCK", todayAt(6, 30))));

            assertThat(statusToday(memberId))
                    .as("SCREEN_TIME 만 읽으면 기상만 보내는 기기는 영영 인증되지 않는다")
                    .isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("[P1] WAKE 는 무시 타입이 아니다 — ignoredSignalTypes 에 담기지 않는다")
        void wakeIsNotIgnored() throws Exception {
            Member me = member(uniq("contract-wake-known"));
            MvcResult res = sync(me.token(), syncBody(List.of(wakeSignal("UNLOCK", todayAt(6, 30)))));

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat(res.getResponse().getContentAsString()).doesNotContain("\"WAKE\"");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("앱 사용 이벤트 보정")
    class UsageEvents {

        @Test
        @DisplayName("[P1] PAUSED 없이 STOPPED 만 와도 사용 구간이 닫힌다")
        void stoppedClosesTheSessionWhenPausedIsMissing() throws Exception {
            Member me = member(uniq("contract-stopped"));
            // 최대 30분 — 강제 종료로 PAUSED 가 빠지면 세션이 자정까지 열려 있는 것으로 계산돼
            // 실제로는 20분만 쓴 사람이 초과 실패가 된다.
            UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MAX", "USAGE", "{\"duration_min\":30}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.ridi.books"));

            syncOk(me.token(), List.of(
                    usageEvent("com.ridi.books", "RESUMED", todayAt(9, 0)),
                    usageEvent("com.ridi.books", "STOPPED", todayAt(9, 20))));

            List<String> reasons = jdbc().queryForList(
                    "SELECT failureReason FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                    String.class, bytes(memberId), java.sql.Date.valueOf(LocalDate.now(KST)));
            String reason = reasons.isEmpty() ? null : reasons.get(0);
            assertThat(reason)
                    .as("종료를 못 읽으면 20분 쓴 사람이 초과 실패로 잡힌다")
                    .isNull();
            assertThat(statusToday(memberId)).isIn(null, "PENDING");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Health Connect 구간")
    class HealthIntervals {

        @Test
        @DisplayName("[P1] 겹치는 구간은 한 번만 집계된다 — 폰과 워치가 같은 산책을 기록해도")
        void overlappingRecordsAreCountedOnce() throws Exception {
            Member me = member(uniq("contract-health-overlap"));
            // 목표 8000 걸음. 같은 구간을 두 기기가 각각 5000 으로 기록했다.
            UUID challenge = insertAutoChallenge(me.id(), "HEALTH", "HC_RECORD",
                    "{\"metric\":\"STEPS\",\"steps\":8000}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, null);

            syncOk(me.token(), List.of(
                    healthSignal("phone-1", 5000, todayAt(9, 0), todayAt(10, 0)),
                    healthSignal("watch-1", 5000, todayAt(9, 0), todayAt(10, 0))));

            assertThat(statusToday(memberId))
                    .as("그냥 더하면 10000 이 되어 목표 8000 을 통과해 버린다")
                    .isIn(null, "PENDING");
        }

        @Test
        @DisplayName("[P1] 겹치지 않는 구간은 합산된다 — 큰 쪽만 쓰면 나머지를 통째로 버린다")
        void disjointRecordsAccumulate() throws Exception {
            Member me = member(uniq("contract-health-sum"));
            UUID challenge = insertAutoChallenge(me.id(), "HEALTH", "HC_RECORD",
                    "{\"metric\":\"STEPS\",\"steps\":8000}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, null);

            syncOk(me.token(), List.of(
                    healthSignal("morning", 5000, todayAt(9, 0), todayAt(10, 0)),
                    healthSignal("evening", 5000, todayAt(19, 0), todayAt(20, 0))));

            assertThat(statusToday(memberId))
                    .as("max 만 취하면 아침 5000 + 저녁 5000 이 5000 이 된다")
                    .isEqualTo("SUCCESS");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("기기·세션")
    class DeviceAndSession {

        @Test
        @DisplayName("[P1] 인트로가 발급한 세션이 저장된다 — 임의 생성으로 끝내지 않는다")
        void introSessionIsPersisted() throws Exception {
            Member me = member(uniq("contract-session"));
            MvcResult res = postJsonAuth("/api/v1/verifications/intro", me.token(),
                    Map.of("deviceId", "device-A", "appVersion", "1.2.3",
                            "deviceProfile", Map.of("sdkInt", 34, "model", "SM-S928N", "lowRam", false)));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            Integer rows = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM verification_sync_sessions WHERE userId = ? AND deviceId = ?",
                    Integer.class, bytes(me.id()), "device-A");
            assertThat(rows).isEqualTo(1);
        }

        @Test
        @DisplayName("[P1] 비활성 기기의 신호는 저장되지만 판정에 쓰이지 않는다")
        void signalsFromAnInactiveDeviceAreStoredButNotJudged() throws Exception {
            Member me = member(uniq("contract-device"));
            UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":30}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.ridi.books"));
            jdbc().update("UPDATE users SET device_id = ? WHERE id = ?", "device-new", bytes(me.id()));

            Map<String, Object> body = syncBody(List.of(
                    usageSignal("com.ridi.books", todayAt(9, 0), todayAt(10, 0))));
            body.put("deviceId", "device-old");   // 교체 전 기기에 남아 있던 백로그
            assertThat(sync(me.token(), body).getResponse().getStatus()).isEqualTo(200);

            assertThat(statusToday(memberId))
                    .as("예전 기기의 백로그가 새 기기의 인증을 통과시키면 안 된다")
                    .isIn(null, "PENDING");
            Integer stored = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM verification_device_usage_signals WHERE userId = ? AND excludeReason IS NOT NULL",
                    Integer.class, bytes(me.id()));
            assertThat(stored).as("원본은 남긴다 — 판정에 안 쓸 뿐 이상탐지 자료다").isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("[P1] 같은 recordId 를 다른 날짜로 보내면 거부한다")
        void sameRecordIdOnAnotherDateIsRejected() throws Exception {
            Member me = member(uniq("contract-recordid"));

            syncOk(me.token(), List.of(healthSignal("rec-1", 5000, todayAt(9, 0), todayAt(10, 0))));
            // 같은 레코드를 어제 발생분으로 다시 올린다 — 귀속일을 바꿔 판정을 다시 받으려는 요청이다.
            syncOk(me.token(), List.of(healthSignal("rec-1", 5000,
                    todayAt(9, 0).minusSeconds(86_400), todayAt(10, 0).minusSeconds(86_400))));

            Integer rows = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM verification_health_connect_signals WHERE userId = ?",
                    Integer.class, bytes(me.id()));
            assertThat(rows)
                    .as("파티션 유일 키는 (발생일, 유저, dedupKey) 라 DB 가 막지 못한다")
                    .isEqualTo(1);
        }
    }
}
