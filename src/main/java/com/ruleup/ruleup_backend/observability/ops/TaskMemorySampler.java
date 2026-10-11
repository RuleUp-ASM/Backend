package com.ruleup.ruleup_backend.observability.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleup.ruleup_backend.observability.metric.MetricContext;
import com.ruleup.ruleup_backend.observability.metric.MetricKey;
import com.ruleup.ruleup_backend.observability.metric.MinuteMetricBuffer;
import com.ruleup.ruleup_backend.observability.metric.MinuteSampler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 이 태스크의 메모리를 <b>하드 한도</b> 대비로 잰다 — 노션 「알람 카탈로그」 §4 ECS-06·RES-01.
 *
 * <p>AWS/ECS 서비스 MemoryUtilization 은 쓸 수 없다. Maximum 이 「태스크별 최고치」가 아니라 시간 구간의 서비스 집계 최고치이고,
 * memoryReservation(soft) 기준일 수 있다. 그래서 각 태스크가 ECS 태스크 메타데이터(v4)에서 컨테이너별 실제 사용량과
 * 태스크·컨테이너 하드 한도를 읽어 {@code RuleUp/Ops MaxMemoryHardPct}(Env, Service) 로 보낸다.
 * 사용량은 Container Insights 와 같게 페이지 캐시(cgroup v1 cache·v2 inactive_file)를 뺀다.
 *
 * <p>모든 태스크가 같은 시계열에 1분 1점을 쓰므로 CloudWatch 통계가 곧 서비스 판정이다 — <b>Maximum</b> 은 가장 찬 태스크
 * (ECS-06 P1), <b>Minimum</b> 은 가장 여유 있는 태스크, <b>SampleCount</b> 는 보고한 태스크 수다. 자원 감시기는
 * 「Minimum ≥99% 이고 SampleCount 가 정상 대상 수 이상」일 때만 RES-01 을 판정한다(한 대라도 보고가 빠지면 판정 보류).
 * TaskId 를 차원으로 쓰지 않아 태스크가 바뀌어도 시계열이 늘지 않는다.
 */
@Slf4j
@Component
@Profile({"prod", "stg"})
public class TaskMemorySampler implements MinuteSampler {

    private static final long MIB = 1024L * 1024L;

    private final MinuteMetricBuffer buffer;
    private final MetricKey key;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String metadataUri = System.getenv("ECS_CONTAINER_METADATA_URI_V4");

    public TaskMemorySampler(MinuteMetricBuffer buffer, MetricContext ctx) {
        this.buffer = buffer;
        this.key = new MetricKey(OpsSignals.OPS, "MaxMemoryHardPct", ctx.dims(), MetricKey.Unit.PERCENT);
    }

    @Override
    public void sample() {
        if (metadataUri == null) return;   // ECS 밖(로컬)
        try {
            Double pct = hardPct(get("/task"), get("/task/stats"));
            if (pct != null) buffer.gauge(key, pct);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("task memory sample failed: {}", e.toString());
        }
    }

    /**
     * 태스크 합계 ÷ 태스크 하드 한도, 그리고 하드 한도가 따로 있는 컨테이너마다 사용 ÷ 그 한도 — 그중 큰 값.
     * 한도를 하나도 못 읽으면 null(0 으로 보내지 않는다).
     */
    static Double hardPct(JsonNode task, JsonNode stats) {
        double worst = -1;
        long totalUsed = 0;
        for (JsonNode container : task.path("Containers")) {
            long used = used(stats.path(container.path("DockerId").asText()));
            if (used < 0) continue;
            totalUsed += used;
            long limitMib = container.path("Limits").path("Memory").asLong(0);
            if (limitMib > 0) worst = Math.max(worst, 100.0 * used / (limitMib * MIB));
        }
        long taskMib = task.path("Limits").path("Memory").asLong(0);
        if (taskMib > 0) worst = Math.max(worst, 100.0 * totalUsed / (taskMib * MIB));
        return worst < 0 ? null : worst;
    }

    private static long used(JsonNode stat) {
        JsonNode mem = stat.path("memory_stats");
        if (mem.isMissingNode() || !mem.has("usage")) return -1;
        JsonNode s = mem.path("stats");
        long cache = s.has("cache") ? s.path("cache").asLong() : s.path("inactive_file").asLong(0);
        return Math.max(0, mem.path("usage").asLong() - cache);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(metadataUri + path))
                .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(r.body());
    }
}
