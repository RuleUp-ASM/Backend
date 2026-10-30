package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.service.VerificationSignalIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 같은 사용자의 sync 가 겹쳐 데드락으로 진 트랜잭션은 처음부터 다시 돈다 — 부하테스트(2026-10) 피크 5xx 11건의 원인.
 * 빈도 제한은 다시 태우지 않으므로 재시도가 자기 첫 시도에 막혀 429 가 되지 않는다.
 * 그래서 이 클래스는 간격을 운영값처럼 켜 둔다 — 꺼 두면 「재시도가 429 가 아니다」를 검증하지 못한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.verification.sync-min-interval-sec=300")
class VerificationSyncDeadlockRetryIT extends VerificationApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoSpyBean VerificationSignalIngestService ingest;
    private MockMvc mvc;

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build(); }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @Test
    @DisplayName("신호 적재가 데드락으로 한 번 지면 다시 돌아 200 으로 끝난다")
    void retriesDeadlockedTransaction() throws Exception {
        Member me = member(uniq("deadlock"));
        doThrow(new CannotAcquireLockException("Deadlock found when trying to get lock; try restarting transaction"))
                .doCallRealMethod()
                .when(ingest).ingest(eq(me.id()), anyList(), any(Instant.class), any(VerificationSignalIngestService.Source.class));

        MvcResult res = postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(steps("dl-1"))));

        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        verify(ingest, times(2)).ingest(eq(me.id()), anyList(), any(Instant.class), any(VerificationSignalIngestService.Source.class));
    }

    @Test
    @DisplayName("계속 지면 정해진 횟수까지만 돈다")
    void givesUpAfterLimit() throws Exception {
        Member me = member(uniq("deadlock-limit"));
        doThrow(new CannotAcquireLockException("Deadlock found when trying to get lock"))
                .when(ingest).ingest(eq(me.id()), anyList(), any(Instant.class), any(VerificationSignalIngestService.Source.class));

        MvcResult res = postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(steps("dl-2"))));

        assertThat(res.getResponse().getStatus()).isGreaterThanOrEqualTo(500);
        verify(ingest, times(3)).ingest(eq(me.id()), anyList(), any(Instant.class), any(VerificationSignalIngestService.Source.class));
    }

    @Test
    @DisplayName("끝내 실패한 sync 는 간격 슬롯을 돌려줘 곧바로 다시 보내도 429 가 아니다")
    void finalFailureReleasesSlot() throws Exception {
        Member me = member(uniq("deadlock-release"));
        CannotAcquireLockException deadlock = new CannotAcquireLockException("Deadlock found when trying to get lock");
        doThrow(deadlock).doThrow(deadlock).doThrow(deadlock)
                .doCallRealMethod()
                .when(ingest).ingest(eq(me.id()), anyList(), any(Instant.class), any(VerificationSignalIngestService.Source.class));

        MvcResult failed = postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(steps("dl-3"))));
        assertThat(failed.getResponse().getStatus()).isGreaterThanOrEqualTo(500);

        MvcResult retried = postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(steps("dl-3"))));
        assertThat(retried.getResponse().getStatus()).as(retried.getResponse().getContentAsString()).isEqualTo(200);

        // 성공한 sync 는 슬롯을 그대로 쥔다.
        MvcResult tooSoon = postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(steps("dl-4"))));
        assertThat(tooSoon.getResponse().getStatus()).isEqualTo(429);
    }

    private static Map<String, Object> steps(String recordId) {
        Instant to = Instant.now(), from = to.minusSeconds(600);
        Map<String, Object> reading = new LinkedHashMap<>();
        reading.put("recordId", recordId);
        reading.put("metric", "STEPS");
        reading.put("value", 100);
        reading.put("unit", "count");
        reading.put("startTime", from.toString());
        reading.put("endTime", to.toString());
        reading.put("origin", Map.of("dataOrigin", "com.sec.android.app.shealth", "recordingMethod", "AUTO", "device", "WATCH"));
        return Map.of("type", "HEALTH", "recordId", recordId, "observedAt", to.toString(), "readings", List.of(reading));
    }
}
