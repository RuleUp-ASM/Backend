package com.ruleup.ruleup_backend.observability.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MinuteMetricBufferTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-10-11T10:00:10Z"));
    private final MinuteMetricBuffer buffer = new MinuteMetricBuffer(clock);
    private final MetricKey eligible = MetricKey.count("RuleUp/SLI", "EligibleCount", Map.of("Env", "prod"));

    @Test
    @DisplayName("진행 중인 분은 내보내지 않고, 끝난 분은 한 번만 내보낸다")
    void drainsOnlyCompletedMinutesOnce() {
        buffer.increment(eligible);
        assertThat(buffer.drainCompleted()).isEmpty();

        clock.advanceSeconds(60);
        List<MinuteMetricBuffer.Datum> first = buffer.drainCompleted();
        assertThat(first).singleElement().satisfies(d -> {
            assertThat(d.value()).isEqualTo(1.0);
            assertThat(d.epochMillis()).isEqualTo(Instant.parse("2026-10-11T10:00:00Z").toEpochMilli());
        });
        assertThat(buffer.drainCompleted()).isEmpty();
    }

    @Test
    @DisplayName("선언한 카운터는 일이 없던 분마다 실제 0 을 싣는다 — 빈 시계열과 0 요청을 구분하는 근거")
    void declaredCounterEmitsExplicitZeroEveryMinute() {
        buffer.declare(eligible);
        clock.advanceSeconds(180);

        List<MinuteMetricBuffer.Datum> data = buffer.drainCompleted();

        assertThat(data).hasSize(3).allSatisfy(d -> assertThat(d.value()).isZero());
        assertThat(data).extracting(MinuteMetricBuffer.Datum::epochMillis).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("분포는 원값을 유효숫자 2자리로 묶어 건수와 함께 싣는다 — CloudWatch 가 p95 를 직접 계산")
    void distributionKeepsValueCounts() {
        MetricKey duration = new MetricKey("RuleUp/SLI", "DurationMs", Map.of("Env", "prod"), MetricKey.Unit.MILLISECONDS);
        buffer.observe(duration, 1234);
        buffer.observe(duration, 1240);
        buffer.observe(duration, 87);
        clock.advanceSeconds(60);

        MinuteMetricBuffer.Datum d = buffer.drainCompleted().get(0);

        assertThat(d.value()).isNull();
        assertThat(d.counts()).containsEntry(1200.0, 2L).containsEntry(87.0, 1L);
    }

    @Test
    @DisplayName("게이지는 같은 분의 최댓값이 남는다")
    void gaugeKeepsMax() {
        MetricKey pool = new MetricKey("RuleUp/Ops", "MaxPoolActivePct", Map.of("Env", "prod"), MetricKey.Unit.PERCENT);
        buffer.gauge(pool, 30);
        buffer.gauge(pool, 70);
        buffer.gauge(pool, 50);
        clock.advanceSeconds(60);

        assertThat(buffer.drainCompleted()).singleElement().extracting(MinuteMetricBuffer.Datum::value).isEqualTo(70.0);
    }

    @Test
    @DisplayName("차원 순서가 달라도 같은 시계열이다")
    void dimensionOrderDoesNotSplitSeries() {
        MetricKey a = MetricKey.count("ns", "m", new java.util.LinkedHashMap<>(Map.of("Env", "prod", "Service", "api")));
        MetricKey b = MetricKey.count("ns", "m", new java.util.LinkedHashMap<>(Map.of("Service", "api", "Env", "prod")));
        assertThat(a).isEqualTo(b);
    }
}
