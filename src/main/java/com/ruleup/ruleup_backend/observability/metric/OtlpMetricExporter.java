package com.ruleup.ruleup_backend.observability.metric;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link MinuteMetricBuffer} 의 끝난 분을 OTLP(HTTP/JSON)로 보낸다 — Grafana Cloud 게이트웨이나 같은 서버의 Alloy 가 받는다.
 *
 * <h4>CloudWatch 와 다른 점</h4>
 * CloudWatch 는 여러 태스크가 같은 timestamp 로 보낸 값을 Sum 으로 합쳐 준다. Prometheus 계열은 같은 시계열에 같은
 * 시각의 값이 둘 오면 하나를 버린다. 그래서 <b>인스턴스를 리소스 속성으로 붙여 시계열을 나누고</b>, 합치기는 조회 쪽이
 * 한다({@code sum by (...) (sum_over_time(...[5m]))}).
 *
 * <h4>왜 전부 게이지인가</h4>
 * 버퍼의 값은 「그 1분의 증분」이다. OTLP Sum 의 delta 는 받는 쪽이 누적으로 바꿔 주지 않으면 버려지므로, 1분 값을
 * 게이지 한 점으로 보낸다. 분포(지연 원값)는 받는 쪽에서 다시 합칠 수 없으니 여기서 건수·합·최대와 p50·p95·p99 를 낸다.
 *
 * <h4>이름</h4>
 * {@code RuleUp/SLI} + {@code EligibleCount} → {@code ruleup_sli_eligible_count}. 차원은 소문자 snake 라벨이 된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.observability.otlp.enabled", havingValue = "true")
public class OtlpMetricExporter {

    static final double[] QUANTILES = {0.5, 0.95, 0.99};

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI uri;
    private final String authorization;
    private final Map<String, String> resource;

    public OtlpMetricExporter(@Value("${app.observability.otlp.endpoint}") String endpoint,
                              @Value("${app.observability.otlp.authorization:}") String authorization,
                              MetricContext context) {
        this.uri = URI.create(endpoint.replaceAll("/+$", "") + "/v1/metrics");
        this.authorization = authorization;
        this.resource = Map.of(
                "service.name", context.service(),
                "service.instance.id", hostname(),
                "deployment.environment", context.env());
    }

    /** 실패하면 그 분을 버리고 로그만 남긴다 — CloudWatch 쪽과 같은 정책이다. */
    public void export(List<MinuteMetricBuffer.Datum> data) {
        if (data.isEmpty()) return;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(toPayload(data, resource))));
            if (!authorization.isBlank()) request.header("Authorization", authorization);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("otlp_metric_export_failed status={} count={} body={}", response.statusCode(), data.size(),
                        response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
            }
        } catch (JsonProcessingException e) {
            log.warn("otlp_metric_export_failed count={}: {}", data.size(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("otlp_metric_export_failed count={}: {}", data.size(), e.toString());
        }
    }

    /** OTLP ExportMetricsServiceRequest(JSON). 같은 이름의 점들은 한 metric 으로 묶는다. */
    static Map<String, Object> toPayload(List<MinuteMetricBuffer.Datum> data, Map<String, String> resource) {
        Map<String, List<Map<String, Object>>> points = new LinkedHashMap<>();
        for (MinuteMetricBuffer.Datum d : data) {
            String name = metricName(d.key());
            String nanos = Long.toString((d.epochMillis() + 59_999L) * 1_000_000L);   // 그 분의 끝 — 늦게 도착한 옛 점으로 버려지지 않게
            List<Map<String, Object>> labels = attributes(labels(d.key()));
            if (d.counts() == null) {
                points.computeIfAbsent(name, k -> new ArrayList<>()).add(point(nanos, labels, d.value()));
                continue;
            }
            long count = 0;
            double sum = 0, max = 0;
            for (Map.Entry<Double, Long> e : d.counts().entrySet()) {
                count += e.getValue();
                sum += e.getKey() * e.getValue();
                max = Math.max(max, e.getKey());
            }
            points.computeIfAbsent(name + "_count", k -> new ArrayList<>()).add(point(nanos, labels, (double) count));
            points.computeIfAbsent(name + "_sum", k -> new ArrayList<>()).add(point(nanos, labels, sum));
            points.computeIfAbsent(name + "_max", k -> new ArrayList<>()).add(point(nanos, labels, max));
            for (double q : QUANTILES) {
                Map<String, String> withQuantile = labels(d.key());
                withQuantile.put("quantile", Double.toString(q));
                points.computeIfAbsent(name, k -> new ArrayList<>())
                        .add(point(nanos, attributes(withQuantile), quantile(d.counts(), count, q)));
            }
        }
        List<Map<String, Object>> metrics = new ArrayList<>();
        points.forEach((name, dataPoints) -> metrics.add(Map.of("name", name, "gauge", Map.of("dataPoints", dataPoints))));
        return Map.of("resourceMetrics", List.of(Map.of(
                "resource", Map.of("attributes", attributes(resource)),
                "scopeMetrics", List.of(Map.of("scope", Map.of("name", "ruleup.minute-buffer"), "metrics", metrics)))));
    }

    /** 원값→건수(오름차순)에서 분위수. 건수가 0 이면 0. */
    static double quantile(Map<Double, Long> counts, long total, double q) {
        if (total == 0) return 0;
        long rank = (long) Math.ceil(q * total);
        long seen = 0;
        double last = 0;
        for (Map.Entry<Double, Long> e : new java.util.TreeMap<>(counts).entrySet()) {
            seen += e.getValue();
            last = e.getKey();
            if (seen >= rank) return last;
        }
        return last;
    }

    static String metricName(MetricKey key) {
        return snake(key.namespace()) + "_" + snake(key.name());
    }

    private static Map<String, String> labels(MetricKey key) {
        Map<String, String> labels = new LinkedHashMap<>();
        key.sortedDimensions().forEach((k, v) -> labels.put(snake(k), v));
        return labels;
    }

    /** {@code RuleUp/SLI} → {@code ruleup_sli}, {@code OldGenAfterGcPct} → {@code old_gen_after_gc_pct}. */
    static String snake(String raw) {
        String spaced = raw.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        return spaced.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "").toLowerCase(Locale.ROOT)
                .replace("rule_up", "ruleup");
    }

    private static Map<String, Object> point(String timeUnixNano, List<Map<String, Object>> attributes, double value) {
        return Map.of("timeUnixNano", timeUnixNano, "asDouble", value, "attributes", attributes);
    }

    private static List<Map<String, Object>> attributes(Map<String, String> pairs) {
        List<Map<String, Object>> out = new ArrayList<>(pairs.size());
        pairs.forEach((k, v) -> out.add(Map.of("key", k, "value", Map.of("stringValue", v))));
        return out;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
