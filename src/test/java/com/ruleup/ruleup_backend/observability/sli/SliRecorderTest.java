package com.ruleup.ruleup_backend.observability.sli;

import com.ruleup.ruleup_backend.common.error.ClientErrorLog;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.config.runtime.RuntimeRole;
import com.ruleup.ruleup_backend.config.runtime.RuntimeRoleProperties;
import com.ruleup.ruleup_backend.observability.metric.MetricContext;
import com.ruleup.ruleup_backend.observability.metric.MinuteMetricBuffer;
import com.ruleup.ruleup_backend.observability.metric.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SliRecorderTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-10-11T10:00:00Z"));
    private final MinuteMetricBuffer buffer = new MinuteMetricBuffer(clock);
    private final SliRecorder recorder = new SliRecorder(buffer,
            new MetricContext("prod", new RuntimeRoleProperties(RuntimeRole.API, false)));

    /** 끝난 분을 꺼내 「이름/Operation → 값」 으로 편다. */
    private Map<String, Double> drain() {
        clock.advanceSeconds(60);
        List<MinuteMetricBuffer.Datum> data = buffer.drainCompleted();
        return data.stream().filter(d -> d.value() != null).collect(Collectors.toMap(
                d -> d.key().name() + "/" + d.key().dimensions().getOrDefault("Operation",
                        d.key().dimensions().getOrDefault("Status", "-")),
                MinuteMetricBuffer.Datum::value, Double::sum));
    }

    @Test
    @DisplayName("요청이 없어도 10개 Operation 의 원천 카운터 3종이 0 으로 나간다")
    void explicitZeroForEveryOperation() {
        Map<String, Double> m = drain();
        for (SliOperation op : SliOperation.values()) {
            assertThat(m).containsEntry("EligibleCount/" + op.id(), 0.0)
                    .containsEntry("AvailabilityBadCount/" + op.id(), 0.0)
                    .containsEntry("LatencyBadCount/" + op.id(), 0.0);
        }
        assertThat(m).containsEntry("LoginCompletedCount/-", 0.0).containsEntry("HttpRejectCount/429", 0.0)
                .containsEntry("ApiRequestCount/-", 0.0);
    }

    @Test
    @DisplayName("지연 실패 = 가용성 실패 + 목표를 넘긴 정상 응답 / 정책 거절은 분모에서 빠지고 거절 수에만 남는다")
    void latencyBadIncludesAvailabilityBad() {
        recorder.record(SliOperation.REFRESH, 200, SliOutcome.GOOD, 300);
        recorder.record(SliOperation.REFRESH, 200, SliOutcome.GOOD, 1500);   // 목표 1초 초과
        recorder.record(SliOperation.REFRESH, 502, SliOutcome.BAD, 100);
        recorder.record(SliOperation.REFRESH, 401, SliOutcome.EXCLUDED, 20);

        Map<String, Double> m = drain();
        assertThat(m).containsEntry("EligibleCount/refresh", 3.0)
                .containsEntry("AvailabilityBadCount/refresh", 1.0)
                .containsEntry("LatencyBadCount/refresh", 2.0)
                .containsEntry("HttpRejectCount/401", 1.0);
    }

    @Test
    @DisplayName("로그인 완료·sync 수신은 정상 응답만 센다(FLOW 처리량)")
    void flowCounters() {
        recorder.record(SliOperation.OAUTH_KAKAO, 200, SliOutcome.GOOD, 100);
        recorder.record(SliOperation.OAUTH_GOOGLE, 502, SliOutcome.BAD, 100);
        recorder.record(SliOperation.SYNC, 200, SliOutcome.GOOD, 100);

        Map<String, Double> m = drain();
        assertThat(m).containsEntry("LoginCompletedCount/-", 1.0).containsEntry("SyncReceivedCount/-", 1.0);
    }

    @Test
    @DisplayName("관리자 서비스는 공개 CUJ 의 0 을 보내지 않는다 — 공개 API 시계열에 섞이지 않게")
    void adminRoleDoesNotDeclarePublicSeries() {
        MinuteMetricBuffer adminBuffer = new MinuteMetricBuffer(clock);
        new SliRecorder(adminBuffer, new MetricContext("prod", new RuntimeRoleProperties(RuntimeRole.ADMIN, false)));
        clock.advanceSeconds(60);
        assertThat(adminBuffer.drainCompleted()).isEmpty();
    }

    @Test
    @DisplayName("필터: 오류 처리기가 남긴 ErrorCode 로 정책 거절을 가르고, 새어 나간 예외는 500 실패로 센다")
    void filterReadsErrorCodeAndCountsEscapedException() throws Exception {
        SliFilter filter = new SliFilter(recorder);

        MockHttpServletRequest rejected = new MockHttpServletRequest("POST", "/api/v1/verifications/sync");
        MockHttpServletResponse tooFrequent = new MockHttpServletResponse();
        filter.doFilter(rejected, tooFrequent, (req, res) -> {
            ClientErrorLog.record((jakarta.servlet.http.HttpServletRequest) req, ErrorCode.SYNC_TOO_FREQUENT);
            ((MockHttpServletResponse) res).setStatus(429);
        });

        MockHttpServletRequest broken = new MockHttpServletRequest("POST", "/api/v1/verifications/sync");
        assertThatThrownBy(() -> filter.doFilter(broken, new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        MockHttpServletRequest probe = new MockHttpServletRequest("POST", "/api/v1/verifications/sync");
        probe.addHeader(SliFilter.SYNTHETIC_HEADER, "edge-tokyo");
        filter.doFilter(probe, new MockHttpServletResponse(), new MockFilterChain());

        Map<String, Double> m = drain();
        assertThat(m).containsEntry("EligibleCount/sync", 1.0)
                .containsEntry("AvailabilityBadCount/sync", 1.0)
                .containsEntry("HttpRejectCount/429", 1.0);
    }

    @Test
    @DisplayName("필터: API 요청 수는 /api/** 만 센다 — 헬스체크·합성 점검은 빼서 배포 사전 점검이 잘못 걸리지 않게")
    void apiRequestCountExcludesHealthAndSynthetic() throws Exception {
        SliFilter filter = new SliFilter(recorder);

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/devices"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/challenges"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest("GET", "/health/public"), new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletRequest probe = new MockHttpServletRequest("GET", "/api/v1/challenges");
        probe.addHeader(SliFilter.SYNTHETIC_HEADER, "edge-oregon");
        filter.doFilter(probe, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(drain()).containsEntry("ApiRequestCount/-", 2.0);
    }
}
