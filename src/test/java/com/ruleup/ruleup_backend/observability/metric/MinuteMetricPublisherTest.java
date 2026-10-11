package com.ruleup.ruleup_backend.observability.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class MinuteMetricPublisherTest {

    @Test
    @DisplayName("네임스페이스별로 묶고, 차원은 이름순, 분포는 150개씩 나눈다")
    void groupsByNamespaceAndSplitsDistributions() {
        MetricKey eligible = MetricKey.count("RuleUp/SLI", "EligibleCount", Map.of("Service", "ruleup-api", "Env", "prod"));
        MetricKey duration = new MetricKey("RuleUp/SLI", "DurationMs", Map.of("Env", "prod"), MetricKey.Unit.MILLISECONDS);
        MetricKey heap = new MetricKey("RuleUp/Ops", "OldGenAfterGcPct", Map.of("Env", "prod"), MetricKey.Unit.PERCENT);
        Map<Double, Long> many = new TreeMap<>();
        for (int i = 1; i <= 200; i++) many.put((double) i, 1L);

        Map<String, List<MetricDatum>> out = MinuteMetricPublisher.toRequests(List.of(
                new MinuteMetricBuffer.Datum(eligible, 60_000L, 0.0, null),
                new MinuteMetricBuffer.Datum(duration, 60_000L, null, many),
                new MinuteMetricBuffer.Datum(heap, 60_000L, 42.0, null)));

        assertThat(out).containsOnlyKeys("RuleUp/SLI", "RuleUp/Ops");
        List<MetricDatum> sli = out.get("RuleUp/SLI");
        assertThat(sli).hasSize(3);
        assertThat(sli.get(0).value()).isZero();
        assertThat(sli.get(0).dimensions()).extracting(d -> d.name()).containsExactly("Env", "Service");
        assertThat(sli.get(1).values()).hasSize(150);
        assertThat(sli.get(2).values()).hasSize(50);
        assertThat(out.get("RuleUp/Ops").get(0).unitAsString()).isEqualTo("Percent");
    }
}
