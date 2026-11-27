package com.ruleup.ruleup_backend.observability.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpMetricExporterTest {

    private static final Map<String, String> RESOURCE = Map.of("service.name", "ruleup-api");

    @Test
    @DisplayName("네임스페이스·이름·차원을 Prometheus 식 이름과 라벨로 바꾼다")
    void namesAndLabels() {
        assertThat(OtlpMetricExporter.snake("RuleUp/SLI")).isEqualTo("ruleup_sli");
        assertThat(OtlpMetricExporter.snake("OldGenAfterGcPct")).isEqualTo("old_gen_after_gc_pct");
        assertThat(OtlpMetricExporter.snake("RuleUp/Prod/Notification")).isEqualTo("ruleup_prod_notification");
        MetricKey key = MetricKey.count("RuleUp/SLI", "EligibleCount", Map.of("Env", "prod", "Operation", "sync"));
        assertThat(OtlpMetricExporter.metricName(key)).isEqualTo("ruleup_sli_eligible_count");

        Map<String, Object> point = points(OtlpMetricExporter.toPayload(
                List.of(new MinuteMetricBuffer.Datum(key, 60_000L, 0.0, null)), RESOURCE), "ruleup_sli_eligible_count").get(0);

        assertThat(point.get("asDouble")).isEqualTo(0.0);   // 실제 0 도 보낸다
        assertThat(point.get("timeUnixNano")).isEqualTo("119999000000");   // 그 분의 끝
        assertThat(labelsOf(point)).containsEntry("env", "prod").containsEntry("operation", "sync");
    }

    @Test
    @DisplayName("분포는 건수·합·최대와 분위수로 풀어 보낸다")
    void distributionBecomesSummary() {
        MetricKey key = new MetricKey("RuleUp/SLI", "DurationMs", Map.of("Env", "prod"), MetricKey.Unit.MILLISECONDS);
        Map<Double, Long> counts = new TreeMap<>();
        counts.put(10.0, 90L);
        counts.put(100.0, 9L);
        counts.put(1000.0, 1L);

        Map<String, Object> payload = OtlpMetricExporter.toPayload(
                List.of(new MinuteMetricBuffer.Datum(key, 0L, null, counts)), RESOURCE);

        assertThat(points(payload, "ruleup_sli_duration_ms_count").get(0).get("asDouble")).isEqualTo(100.0);
        assertThat(points(payload, "ruleup_sli_duration_ms_sum").get(0).get("asDouble")).isEqualTo(2800.0);
        assertThat(points(payload, "ruleup_sli_duration_ms_max").get(0).get("asDouble")).isEqualTo(1000.0);
        List<Map<String, Object>> quantiles = points(payload, "ruleup_sli_duration_ms");
        assertThat(quantiles).hasSize(3);
        assertThat(quantiles).filteredOn(p -> "0.5".equals(labelsOf(p).get("quantile")))
                .singleElement().satisfies(p -> assertThat(p.get("asDouble")).isEqualTo(10.0));
        assertThat(quantiles).filteredOn(p -> "0.95".equals(labelsOf(p).get("quantile")))
                .singleElement().satisfies(p -> assertThat(p.get("asDouble")).isEqualTo(100.0));
        assertThat(quantiles).filteredOn(p -> "0.99".equals(labelsOf(p).get("quantile")))
                .singleElement().satisfies(p -> assertThat(p.get("asDouble")).isEqualTo(100.0));
    }

    @Test
    @DisplayName("같은 이름의 점들은 한 metric 으로 묶인다")
    void groupsPointsByName() {
        MetricKey a = MetricKey.count("RuleUp/SLI", "EligibleCount", Map.of("Operation", "sync"));
        MetricKey b = MetricKey.count("RuleUp/SLI", "EligibleCount", Map.of("Operation", "join"));

        Map<String, Object> payload = OtlpMetricExporter.toPayload(List.of(
                new MinuteMetricBuffer.Datum(a, 0L, 3.0, null), new MinuteMetricBuffer.Datum(b, 0L, 5.0, null)), RESOURCE);

        assertThat(metrics(payload)).hasSize(1);
        assertThat(points(payload, "ruleup_sli_eligible_count")).hasSize(2);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> metrics(Map<String, Object> payload) {
        Map<String, Object> resourceMetrics = ((List<Map<String, Object>>) payload.get("resourceMetrics")).get(0);
        Map<String, Object> scope = ((List<Map<String, Object>>) resourceMetrics.get("scopeMetrics")).get(0);
        return (List<Map<String, Object>>) scope.get("metrics");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> points(Map<String, Object> payload, String name) {
        Map<String, Object> metric = metrics(payload).stream().filter(m -> name.equals(m.get("name"))).findFirst().orElseThrow();
        return (List<Map<String, Object>>) ((Map<String, Object>) metric.get("gauge")).get("dataPoints");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> labelsOf(Map<String, Object> point) {
        Map<String, String> out = new TreeMap<>();
        for (Map<String, Object> attribute : (List<Map<String, Object>>) point.get("attributes")) {
            out.put((String) attribute.get("key"), (String) ((Map<String, Object>) attribute.get("value")).get("stringValue"));
        }
        return out;
    }
}
