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
 * <b>분할 전송 순서가 판정을 바꾸지 않는지</b> 검증한다 (백엔드 테크스펙 4-3 「신호 수신」).
 *
 * <p>앱은 한 번에 보낼 양이 넘치면 구간을 쪼개 순차 전송하고, 그 요청들은 순서가 뒤바뀔 수 있다.
 * 판정이 "이번 요청의 신호 + 직전 요약"으로 굴러가면 짝을 못 찾은 앞 이벤트가 <b>영구히</b>
 * 버려진다 — PAUSED 가 RESUMED 보다 먼저 도착하면 그 사용 구간은 영영 세어지지 않는다.
 *
 * <p>그래서 판정 입력을 저장된 원본으로 바꿨다. 평가는 매번 그날 원본을 통째로 다시 읽어
 * 처음부터 계산하므로 도착 순서와 무관하다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationSplitOrderIT extends VerificationApiSupport {

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

    private void sync(String token, List<Map<String, Object>> signals) throws Exception {
        assertThat(postJsonAuth("/api/v1/verifications/sync", token, syncBody(signals))
                .getResponse().getStatus()).isEqualTo(200);
    }

    /** 앱 사용 이벤트 한 개짜리 신호 — 구간을 쪼개 보내는 상황을 그대로 만든다. */
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

    private void startedDaysAgo(UUID challengeId, int days) {
        jdbc().update("UPDATE challenges SET start_date = " +
                        " DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY) WHERE id = ?",
                days, bytes(challengeId));
    }

    private String statusOn(UUID challengeMemberId, LocalDate date) {
        List<String> rows = jdbc().queryForList(
                "SELECT status FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                String.class, bytes(challengeMemberId), java.sql.Date.valueOf(date));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    @DisplayName("[P0] PAUSED 가 RESUMED 보다 먼저 도착해도 사용 구간이 세어진다")
    void pausedArrivingBeforeResumedStillCounts() throws Exception {
        Member me = member(uniq("split-usage"));
        UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":30}");
        UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.ridi.books"));

        // 분할 전송이 뒤바뀌었다 — 종료가 먼저 오고 시작이 나중에 온다.
        sync(me.token(), List.of(usageEvent("com.ridi.books", "PAUSED", todayAt(9, 40))));
        assertThat(statusOn(memberId, LocalDate.now(KST))).isIn(null, "PENDING");

        sync(me.token(), List.of(usageEvent("com.ridi.books", "RESUMED", todayAt(9, 0))));

        assertThat(statusOn(memberId, LocalDate.now(KST)))
                .as("앞 이벤트를 버리면 40분을 실제로 쓴 사람이 영영 인증되지 않는다")
                .isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("[P0] EXIT 이 ENTER 보다 먼저 도착해도 체류가 세어진다")
    void exitArrivingBeforeEnterStillCounts() throws Exception {
        Member me = member(uniq("split-geo"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

        sync(me.token(), List.of(geofenceSignal(memberId, "EXIT", todayAt(10, 0))));
        assertThat(statusOn(memberId, LocalDate.now(KST))).isIn(null, "PENDING");

        sync(me.token(), List.of(geofenceSignal(memberId, "ENTER", todayAt(9, 0))));

        assertThat(statusOn(memberId, LocalDate.now(KST)))
                .as("짝을 못 찾은 EXIT 을 버리면 한 시간 머문 사람이 인증되지 않는다")
                .isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("[P0] 같은 신호가 다시 들어와도 사용 시간이 부풀지 않는다")
    void replayDoesNotInflateUsage() throws Exception {
        Member me = member(uniq("split-replay"));
        // 목표 60분 — 40분 구간을 두 번 세면 80분이 되어 통과해 버린다.
        UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":60}");
        UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.ridi.books"));

        Map<String, Object> block = usageSignal("com.ridi.books", todayAt(20, 0), todayAt(20, 40));
        sync(me.token(), List.of(block));
        sync(me.token(), List.of(block));

        assertThat(statusOn(memberId, LocalDate.now(KST)))
                .as("전량 재평가가 중복 제거를 잃으면 재전송만으로 인증이 통과한다")
                .isIn(null, "PENDING");
    }

    @Test
    @DisplayName("[P0] 확정 배치도 저장된 요약이 아니라 원본으로 최종 재평가한다")
    void finalizeReevaluatesFromRawSignals() throws Exception {
        Member me = member(uniq("split-finalize"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        startedDaysAgo(challenge, 5);

        LocalDate twoDaysAgo = LocalDate.now(KST).minusDays(2);
        Instant enter = twoDaysAgo.atTime(9, 0).atZone(KST).toInstant();
        Instant exit = twoDaysAgo.atTime(10, 0).atZone(KST).toInstant();

        // 그저께 다녀온 기록이 이제야 올라온다. 그 날짜는 sync 가 평가하지 않는다 —
        // 유예 구간은 어제까지고, 확정 시각은 이미 지났다. 그래서 확정기가 원본을 봐야 한다.
        sync(me.token(), List.of(
                geofenceSignal(memberId, "ENTER", enter),
                geofenceSignal(memberId, "EXIT", exit)));

        finalizeService.materializeDueTargets();
        finalizeService.finalizeDue();

        assertThat(statusOn(memberId, twoDaysAgo))
                .as("저장된 요약만 보면 한 번도 평가되지 않은 날은 무조건 실패가 된다")
                .isEqualTo("SUCCESS");
    }
}
