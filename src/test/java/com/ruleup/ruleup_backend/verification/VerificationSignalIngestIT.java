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
import com.ruleup.ruleup_backend.verification.config.SyncPayloadSizeFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 신호 수신 — 원본 저장 · 영속 멱등 · 요청 크기 상한 (인증 구현 백엔드 테크스펙 §4-3).
 *
 * <p>같은 신호는 여러 번 도착하는 것이 정상이다. 오프라인 복구, FCM 기동 후 일괄 전송, 구간 재전송이
 * 전부 재전송을 만든다. 그래서 <b>중복 수신 자체는 정상 경로</b>로 취급하되, 판정에는 한 번만 반영해야 한다.
 * 멱등을 평가기 안의 메모리 상태나 evidence 에만 맡기면 평가기마다 다시 구현해야 하고 한 곳이라도 빠지면
 * 사용 시간이 두 배가 된다 — 그래서 수신 지점에서 영속 dedup 으로 끊는다.
 *
 * <p>요청 크기는 본문 바이트로 제한한다. 경로상의 상한(CDN·ALB)은 병목이 아니고, 실제 병목은
 * JSON 본문을 통째로 힙에 올리는 파싱 메모리다. Tomcat 의 기본 POST 제한은 form 인코딩에만 적용돼
 * JSON 에는 사실상 상한이 없으므로 명시적으로 걸어야 한다.
 */
@SpringBootTest(properties = "app.verification.max-payload-bytes=4096")
@Import(TestcontainersConfiguration.class)
class VerificationSignalIngestIT extends VerificationApiSupport {

    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    /** 운영에서는 Boot 가 자동 등록하는 필터라, MockMvc 에도 같은 체인으로 끼워 넣어야 실제 경로와 같아진다. */
    @Autowired SyncPayloadSizeFilter payloadSizeFilter;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac)
                .addFilters(payloadSizeFilter)
                .apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private MvcResult sync(String token, List<Map<String, Object>> signals) throws Exception {
        return postJsonAuth("/api/v1/verifications/sync", token, syncBody(signals));
    }

    private MvcResult syncOk(String token, List<Map<String, Object>> signals) throws Exception {
        MvcResult res = sync(token, signals);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res;
    }

    private int storedSignalsOf(UUID userId) {
        Integer n = jdbc().queryForObject(
                "SELECT (SELECT COUNT(*) FROM verification_location_signals WHERE userId = ?)"
                        + " + (SELECT COUNT(*) FROM verification_device_usage_signals WHERE userId = ?)"
                        + " + (SELECT COUNT(*) FROM verification_health_connect_signals WHERE userId = ?)",
                Integer.class, bytes(userId), bytes(userId), bytes(userId));
        return n != null ? n : 0;
    }

    private static Map<String, Object> withRecordId(Map<String, Object> signal, String recordId) {
        Map<String, Object> copy = new LinkedHashMap<>(signal);
        copy.put("recordId", recordId);
        return copy;
    }

    private static String visitParams() { return "{\"duration_min\":30,\"radius_m\":100}"; }

    private MvcResult syncFrom(String token, String deviceId, List<Map<String, Object>> signals)
            throws Exception {
        Map<String, Object> body = syncBody(signals);
        body.put("deviceId", deviceId);
        MvcResult res = postJsonAuth("/api/v1/verifications/sync", token, body);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res;
    }

    private String excludeReasonOf(UUID userId) {
        return excludeReasonOf("verification_location_signals", userId);
    }

    private String excludeReasonOf(String table, UUID userId) {
        return jdbc().queryForObject(
                "SELECT excludeReason FROM " + table + " WHERE userId = ?", String.class, bytes(userId));
    }

    private java.sql.Timestamp receivedAtOf(UUID userId) {
        return jdbc().queryForObject(
                "SELECT receivedAt FROM verification_location_signals WHERE userId = ?",
                java.sql.Timestamp.class, bytes(userId));
    }

    private int countIn(String table, UUID userId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE userId = ?", Integer.class, bytes(userId));
        return n != null ? n : 0;
    }

    private int dayPartitionsOf(String table) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.PARTITIONS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                        + " AND PARTITION_NAME LIKE 'p2%'", Integer.class, table);
        return n != null ? n : 0;
    }

    // =====================================================================
    @Nested
    @DisplayName("저장 도메인")
    class Domains {

        @Test
        @DisplayName("입력 타입은 5종 그대로지만 저장은 셋으로 갈린다 — 위치와 앱 사용이 같은 테이블에 섞이지 않는다")
        void signalsAreRoutedByDomain() throws Exception {
            Member me = member(uniq("domain"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            syncOk(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    usageSignal("com.example.app", todayAt(10, 0), todayAt(10, 30))));

            assertThat(countIn("verification_location_signals", me.id()))
                    .as("지오펜스는 위치 도메인").isEqualTo(1);
            assertThat(countIn("verification_device_usage_signals", me.id()))
                    .as("앱 사용은 기기 사용 도메인").isEqualTo(1);
            assertThat(countIn("verification_health_connect_signals", me.id())).isZero();
        }

        @Test
        @DisplayName("일자 파티션이 미리 잘려 있다 — 없으면 만료분을 날짜별로 떨어뜨릴 수 없다")
        void dayPartitionsArePreparedAhead() {
            // 기동 시 정비가 최소 7일 앞을 확보한다(스펙 4-1-1).
            assertThat(dayPartitionsOf("verification_location_signals")).isGreaterThanOrEqualTo(7);
            assertThat(dayPartitionsOf("verification_device_usage_signals")).isGreaterThanOrEqualTo(7);
            assertThat(dayPartitionsOf("verification_health_connect_signals")).isGreaterThanOrEqualTo(7);
        }
    }

    @Nested
    @DisplayName("원본 저장")
    class RawStorage {

        @Test
        @DisplayName("도착한 신호는 원본 그대로 저장된다 — 부정행위 검증과 기준값 재계산의 근거다")
        void signalsAreStored() throws Exception {
            Member me = member(uniq("ingest-store"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            syncOk(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(10, 0))));

            assertThat(storedSignalsOf(me.id())).isEqualTo(2);
        }

        @Test
        @DisplayName("셋업 전(PENDING_SETUP)이라 평가하지 않는 신호도 저장은 한다")
        void signalsAreStoredEvenWhenEvaluationIsSkipped() throws Exception {
            Member me = member(uniq("ingest-nosetup"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            jdbc().update("UPDATE challenge_members SET setup_status = 'PENDING_SETUP' WHERE id = ?", bytes(memberId));

            syncOk(me.token(), List.of(geofenceSignal(memberId, "ENTER", todayAt(9, 0))));

            assertThat(storedSignalsOf(me.id())).isEqualTo(1);
            assertThat(todayStatusOf(memberId)).as("평가는 건너뛴다").isIn(null, "PENDING");
        }
    }

    @Nested
    @DisplayName("영속 멱등")
    class Idempotency {

        @Test
        @DisplayName("같은 recordId 를 다시 보내면 저장도 판정도 한 번만 반영된다")
        void sameRecordIdIsProcessedOnce() throws Exception {
            Member me = member(uniq("ingest-record"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            Map<String, Object> enter = withRecordId(geofenceSignal(memberId, "ENTER", todayAt(9, 0)), "rec-1");
            Map<String, Object> exit = withRecordId(geofenceSignal(memberId, "EXIT", todayAt(9, 20)), "rec-2");

            syncOk(me.token(), List.of(enter, exit));
            long firstDwell = dwellMinutesOf(memberId);

            MvcResult again = syncOk(me.token(), List.of(enter, exit));   // 오프라인 복구 재전송

            assertThat(storedSignalsOf(me.id())).as("중복은 저장되지 않는다").isEqualTo(2);
            assertThat(dwellMinutesOf(memberId))
                    .as("재전송이 체류 시간을 두 배로 만들면 안 된다")
                    .isEqualTo(firstDwell);
            assertThat((Integer) read(again, "$.data.dedupDroppedCount"))
                    .as("몇 건이 중복으로 걸렸는지 회신한다(sync_result 로깅 입력)")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("다시 보내오면 본문은 그대로 두고 수신 시각만 앞으로 민다")
        void resendAdvancesReceivedAt() throws Exception {
            // 「언제 다시 주장했는가」가 판정 입력인 신호가 있다(수면의 미래 구간 판정).
            // 최초 수신 시각에 묶어 두면, 그때 유효하지 않았던 기록이 정상 재전송으로도 되살아나지 못한다.
            Member me = member(uniq("ingest-resend"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            Map<String, Object> enter = withRecordId(geofenceSignal(memberId, "ENTER", todayAt(9, 0)), "resend-1");
            syncOk(me.token(), List.of(enter));
            java.sql.Timestamp first = receivedAtOf(me.id());

            // 같은 신호를 한 번 더 — 저장은 늘지 않아야 하고, 수신 시각은 뒤로 가지 않아야 한다.
            syncOk(me.token(), List.of(enter));

            assertThat(storedSignalsOf(me.id())).as("본문이 두 번 저장되지는 않는다").isEqualTo(1);
            assertThat(receivedAtOf(me.id()))
                    .as("다시 보내온 시각으로 갱신된다 — 최초 수신 시각에 묶이지 않는다")
                    .isAfter(first);
        }

        @Test
        @DisplayName("비활성 기기의 재전송은 기존 신호의 수신 시각을 밀지 못한다")
        void resendFromInactiveDeviceDoesNotAdvanceReceivedAt() throws Exception {
            // 수신 시각을 미는 것은 「지금 이 주장을 믿는다」는 뜻이다. 그 주장을 못 믿을 기기가
            // 해도 밀린다면, 예전 기기가 같은 recordId 만 알면 새 기기의 판정을 움직일 수 있다 —
            // 배제 사유를 행에 새겨 둔 의미가 사라진다.
            Member me = member(uniq("ingest-inactive-resend"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            jdbc().update("UPDATE users SET device_id = ? WHERE id = ?", "device-A", bytes(me.id()));

            Map<String, Object> enter = withRecordId(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)), "inactive-resend-1");
            syncFrom(me.token(), "device-A", List.of(enter));
            java.sql.Timestamp first = receivedAtOf(me.id());

            // 교체 전 기기가 같은 신호를 다시 올린다. 이 요청의 신호는 전부 UNTRUSTED_SOURCE 다.
            syncFrom(me.token(), "old-device", List.of(enter));

            assertThat(receivedAtOf(me.id()))
                    .as("못 믿을 기기의 재전송이 「다시 주장한 시각」을 만들어 주면 안 된다")
                    .isEqualTo(first);
            assertThat(excludeReasonOf(me.id()))
                    .as("기존 행은 믿을 수 있는 기기의 것 그대로다").isNull();
        }

        @Test
        @DisplayName("교체 전 기기가 먼저 올린 기록도 활성 기기가 다시 올리면 인정된다")
        void trustedResendRevivesAnExcludedRecord() throws Exception {
            // 배제는 기록이 아니라 봉투의 성질이다. 멱등은 기록 단위라, 교체 전 기기가 백로그를
            // 먼저 흘려보내면 그 recordId 들이 배제된 채 자리를 차지하고 — 새 기기가 같은 기록을
            // 올려도 중복으로 걸려 영영 판정에 쓰이지 않는다. 기기를 바꾼 사용자의 사용 시간이
            // 조용히 통째로 사라지는 경로다.
            Member me = member(uniq("ingest-revive"));
            UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":30}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.ridi.books"));
            jdbc().update("UPDATE users SET device_id = ? WHERE id = ?", "device-A", bytes(me.id()));

            Map<String, Object> usage = withRecordId(
                    usageSignal("com.ridi.books", todayAt(9, 0), todayAt(10, 0)), "revive-1");

            syncFrom(me.token(), "old-device", List.of(usage));
            assertThat(todayStatusOf(memberId)).as("비활성 기기 신호는 판정에 쓰지 않는다").isEqualTo("PENDING");

            syncFrom(me.token(), "device-A", List.of(usage));

            assertThat(todayStatusOf(memberId))
                    .as("믿을 수 있는 기기가 같은 기록을 올렸으면 그 주장은 인정돼야 한다")
                    .isEqualTo("SUCCESS");
            assertThat(excludeReasonOf("verification_device_usage_signals", me.id())).isNull();
        }

        @Test
        @DisplayName("배제를 풀 때 인정되는 본문은 믿을 수 있는 기기가 보낸 쪽이다")
        void revivedRecordKeepsTheTrustedPayload() throws Exception {
            // 배제만 풀고 본문을 남기면, 못 믿을 기기가 recordId 를 선점해 유리한 본문을 심어 두고
            // 깨끗한 기기의 정상 전송이 그것을 인정해 주는 꼴이 된다.
            Member me = member(uniq("ingest-plant"));
            UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":30}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.ridi.books"));
            jdbc().update("UPDATE users SET device_id = ? WHERE id = ?", "device-A", bytes(me.id()));

            // 심는 쪽: 같은 recordId 에 60분짜리 본문.
            syncFrom(me.token(), "old-device", List.of(withRecordId(
                    usageSignal("com.ridi.books", todayAt(9, 0), todayAt(10, 0)), "plant-1")));
            // 실제 기기가 보낸 같은 기록: 10분.
            syncFrom(me.token(), "device-A", List.of(withRecordId(
                    usageSignal("com.ridi.books", todayAt(9, 0), todayAt(9, 10)), "plant-1")));

            assertThat(todayStatusOf(memberId))
                    .as("심어 둔 60분이 아니라 실제로 보낸 10분이 인정돼야 한다")
                    .isEqualTo("PENDING");
        }

        @Test
        @DisplayName("recordId 가 없어도 같은 내용의 신호는 한 번만 반영된다")
        void identicalSignalsWithoutRecordIdAreDeduped() throws Exception {
            Member me = member(uniq("ingest-norecord"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            List<Map<String, Object>> batch = List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(9, 20)));

            syncOk(me.token(), batch);
            long firstDwell = dwellMinutesOf(memberId);
            syncOk(me.token(), batch);

            assertThat(storedSignalsOf(me.id())).isEqualTo(2);
            assertThat(dwellMinutesOf(memberId)).isEqualTo(firstDwell);
        }

        @Test
        @DisplayName("앱 사용 구간을 재전송해도 사용 시간이 두 배가 되지 않는다")
        void resentUsageWindowDoesNotDoubleCount() throws Exception {
            Member me = member(uniq("ingest-usage"));
            UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":60}");
            UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.ridi.books"));

            Map<String, Object> usage = usageSignal("com.ridi.books", todayAt(20, 0), todayAt(20, 40));
            syncOk(me.token(), List.of(usage));
            assertThat(todayStatusOf(memberId)).as("40분 < 목표 60분").isEqualTo("PENDING");

            syncOk(me.token(), List.of(usage));   // 같은 구간 재전송

            assertThat(todayStatusOf(memberId))
                    .as("40분을 두 번 세면 80분이 되어 목표를 넘겨버린다")
                    .isEqualTo("PENDING");
        }

        @Test
        @DisplayName("한 요청 안에 같은 신호가 두 번 들어와도 한 번만 반영된다")
        void duplicatesWithinOneRequestAreCollapsed() throws Exception {
            Member me = member(uniq("ingest-inbatch"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            Map<String, Object> enter = geofenceSignal(memberId, "ENTER", todayAt(9, 0));
            syncOk(me.token(), List.of(enter, enter, enter));

            assertThat(storedSignalsOf(me.id())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("나눠 보내기")
    class ChunkedDelivery {

        @Test
        @DisplayName("구간을 쪼개 보내면 하나의 날짜 판정으로 합쳐진다 — 순서가 바뀌어도 같다")
        void chunksMergeIntoOneDayRegardlessOfOrder() throws Exception {
            Member me = member(uniq("ingest-chunk"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            // 뒷 구간을 먼저, 앞 구간을 나중에 — 순번·마지막 플래그가 없어도 안전해야 한다.
            syncOk(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(14, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(14, 20))));
            syncOk(me.token(), List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(9, 15))));

            assertThat(dwellMinutesOf(memberId)).as("20분 + 15분").isEqualTo(35L);
            assertThat(todayStatusOf(memberId)).as("목표 30분을 넘겼다").isEqualTo("SUCCESS");
        }
    }

    @Nested
    @DisplayName("요청 크기 상한")
    class PayloadLimit {

        @Test
        @DisplayName("본문이 상한을 넘으면 413 으로 반려하고 아무것도 저장하지 않는다")
        void oversizedBodyIsRejectedBeforeAnythingIsStored() throws Exception {
            Member me = member(uniq("ingest-toobig"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            List<Map<String, Object>> many = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                many.add(geofenceSignal(memberId, (i % 2 == 0) ? "ENTER" : "EXIT", todayAt(1 + i / 60, i % 60)));
            }

            MvcResult res = sync(me.token(), many);

            expectError(res, 413, "SYNC_PAYLOAD_TOO_LARGE");
            assertThat(storedSignalsOf(me.id())).as("파싱 전에 끊는다").isZero();
            assertThat(todayStatusOf(memberId)).isIn(null, "PENDING");
        }

        @Test
        @DisplayName("상한 안의 요청은 그대로 처리되고, 응답이 클라에 상한을 알려준다")
        void withinLimitIsProcessedAndLimitIsAdvertised() throws Exception {
            Member me = member(uniq("ingest-fits"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            MvcResult res = syncOk(me.token(), List.of(geofenceSignal(memberId, "ENTER", todayAt(9, 0))));

            assertThat((Integer) read(res, "$.data.maxPayloadBytes")).isEqualTo(4096);
            assertThat(storedSignalsOf(me.id())).isEqualTo(1);
        }
    }
}
