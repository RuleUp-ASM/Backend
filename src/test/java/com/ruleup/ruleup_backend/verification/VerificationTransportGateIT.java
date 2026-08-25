package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.config.SyncPayloadSizeFilter;
import com.ruleup.ruleup_backend.verification.config.SyncRequestDecompressFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 전송 계약 — 요청 gzip 과 신호 품질 게이트 (백엔드 테크스펙 §4-3 신호 게이트 · §5 요청 크기 상한).
 *
 * <p>API 명세는 요청 gzip 을 지원한다고 적어 뒀지만 서버는 해제하지 않고 있었다. 그래서 상한도
 * "압축 후 바이트"만 걸려 있었다 — 압축률이 높은 요청 하나로 힙을 고갈시킬 수 있는 형태다.
 * 스펙이 요구하는 것은 <b>본문 바이트와 압축 해제 후 누적 바이트를 둘 다</b> 세는 것이다.
 *
 * <p>{@code integrity}·{@code network.vpnActive} 도 DTO 에만 있고 소비처가 없었다. 스펙은
 * "명백한 비정상 신호를 제외하는 것과 사용자를 부정행위자로 확정하는 것을 분리"하라고 한다 —
 * 그래서 판정 입력에서 빼기만 하고 제재하지 않는다.
 */
@SpringBootTest(properties = "app.verification.max-payload-bytes=4096")
@Import(TestcontainersConfiguration.class)
class VerificationTransportGateIT extends VerificationApiSupport {

    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SyncPayloadSizeFilter payloadSizeFilter;
    @Autowired SyncRequestDecompressFilter decompressFilter;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 운영에서는 Boot 가 자동 등록한다. 순서도 같게 — 해제 먼저, 그다음 크기 검사.
        mvc = MockMvcBuilders.webAppContextSetup(wac)
                .addFilters(decompressFilter, payloadSizeFilter)
                .apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static String visitParams() { return "{\"duration_min\":30,\"radius_m\":100}"; }

    private static byte[] gzip(String body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private MvcResult postGzip(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/verifications/sync")
                .header("Authorization", "Bearer " + token)
                .header("Content-Encoding", "gzip")
                .contentType(MediaType.APPLICATION_JSON)
                .content(gzip(OM.writeValueAsString(body)))).andReturn();
    }

    private int storedSignalsOf(UUID userId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM verification_signals WHERE userId = ?", Integer.class, bytes(userId));
        return n != null ? n : 0;
    }

    private Long dwellOf(UUID memberId) {
        return dwellMinutesOf(memberId);
    }

    @Nested
    @DisplayName("요청 gzip")
    class Gzip {

        @Test
        @DisplayName("gzip 으로 보낸 요청도 정상 처리된다 — 명세대로 지원한다")
        void gzippedRequestIsAccepted() throws Exception {
            Member me = member(uniq("gz-ok"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            MvcResult res = postGzip(me.token(), syncBody(List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(10, 0)))));

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("압축 후에는 작아도 풀면 상한을 넘는 요청은 반려된다 — 압축률로 힙을 노릴 수 없다")
        void highlyCompressibleBombIsRejected() throws Exception {
            Member me = member(uniq("gz-bomb"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            // 같은 전환을 잔뜩 — 압축하면 아주 작지만 풀면 상한(4KB)을 훌쩍 넘는다.
            List<Map<String, Object>> many = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                many.add(geofenceSignal(memberId, "ENTER", todayAt(1 + i / 60, i % 60)));
            }
            byte[] compressed = gzip(OM.writeValueAsString(syncBody(many)));
            assertThat(compressed.length).as("압축 후에는 상한보다 작다").isLessThan(4096);

            MvcResult res = mvc.perform(post("/api/v1/verifications/sync")
                    .header("Authorization", "Bearer " + me.token())
                    .header("Content-Encoding", "gzip")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(compressed)).andReturn();

            expectError(res, 413, "SYNC_PAYLOAD_TOO_LARGE");
            assertThat(storedSignalsOf(me.id())).as("본문 전체가 힙에 올라오기 전에 끊는다").isZero();
        }
    }

    @Nested
    @DisplayName("신호 품질 게이트")
    class SignalGate {

        /** 봉투에 진단 필드를 얹는다. */
        private Map<String, Object> envelopeWith(List<Map<String, Object>> signals, String key, Object value) {
            Map<String, Object> body = new LinkedHashMap<>(syncBody(signals));
            body.put(key, value);
            return body;
        }

        @Test
        @DisplayName("VPN 이 켜진 구간의 위치 신호는 판정 입력에서 빠진다 — 위치를 신뢰할 수 없다")
        void vpnActiveExcludesLocationSignals() throws Exception {
            Member me = member(uniq("gate-vpn"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            MvcResult res = postJsonAuth("/api/v1/verifications/sync", me.token(),
                    envelopeWith(List.of(
                                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                                    geofenceSignal(memberId, "EXIT", todayAt(10, 0))),
                            "network", Map.of("vpnActive", true)));

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat(todayStatusOf(memberId)).isIn(null, "PENDING");
            assertThat(dwellOf(memberId)).as("체류로 세지 않는다").isIn(null, 0L);
            assertThat(storedSignalsOf(me.id())).as("원본은 그대로 저장한다 — 이상탐지 자료다").isEqualTo(2);
        }

        @Test
        @DisplayName("무결성 검증에 실패한 기기의 위치 신호도 판정에서 빠진다")
        void failedIntegrityExcludesLocationSignals() throws Exception {
            Member me = member(uniq("gate-integrity"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            MvcResult res = postJsonAuth("/api/v1/verifications/sync", me.token(),
                    envelopeWith(List.of(
                                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                                    geofenceSignal(memberId, "EXIT", todayAt(10, 0))),
                            "integrity", Map.of("verdict", "FAIL")));

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat(todayStatusOf(memberId)).isIn(null, "PENDING");
        }

        @Test
        @DisplayName("게이트에 걸려도 요청 자체는 성공하고 제재하지 않는다 — 제외와 처벌은 분리한다")
        void gateDoesNotPunish() throws Exception {
            Member me = member(uniq("gate-nopunish"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            MvcResult res = postJsonAuth("/api/v1/verifications/sync", me.token(),
                    envelopeWith(List.of(geofenceSignal(memberId, "ENTER", todayAt(9, 0))),
                            "network", Map.of("vpnActive", true)));

            assertThat(res.getResponse().getStatus()).as("에러가 아니다").isEqualTo(200);
            assertThat(todayStatusOf(memberId)).as("실패로 확정하지도 않는다").isIn(null, "PENDING");
        }

        @Test
        @DisplayName("정상 기기의 신호는 그대로 판정된다")
        void cleanDeviceIsUnaffected() throws Exception {
            Member me = member(uniq("gate-clean"));
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            postJsonAuth("/api/v1/verifications/sync", me.token(),
                    envelopeWith(List.of(
                                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                                    geofenceSignal(memberId, "EXIT", todayAt(10, 0))),
                            "integrity", Map.of("verdict", "MEETS_DEVICE_INTEGRITY")));

            assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
        }
    }
}
