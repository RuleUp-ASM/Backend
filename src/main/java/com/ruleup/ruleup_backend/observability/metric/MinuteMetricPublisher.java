package com.ruleup.ruleup_backend.observability.metric;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link MinuteMetricBuffer} 의 끝난 분을 CloudWatch 로 보낸다 — <b>태스크마다</b> 돈다(ShedLock 없음).
 *
 * <p>각 태스크가 자기 몫을 같은 분 timestamp 로 보내면 CloudWatch 가 Sum 으로 합친다. 한 대가 죽어도 나머지가 계속
 * 보내므로 서비스 시계열이 끊기지 않는다. 전송 실패는 그 분을 버리고 로그만 남긴다 — 다시 쌓아 두면 장애 중에
 * 메모리만 커지고, 끊긴 구간은 SLO 계산기가 coverage 로 드러낸다(0 으로 메우지 않는다).
 *
 * <p>스프링 스케줄러({@code app.scheduling.enabled})에 기대지 않고 자기 스레드 하나로 돈다. 관리자 서비스는 배치를
 * 끄려고 스케줄러를 내리지만, 그 서비스의 JVM·풀·로그인 실패 지표는 계속 나가야 한다. 30초마다 {@link MinuteSampler}
 * 를 부르고, 매분 5초 틱에 직전 분을 보낸다.
 */
@Slf4j
@Component
@Profile({"prod", "stg"})
@ConditionalOnProperty(name = "app.observability.cloudwatch.enabled", havingValue = "true", matchIfMissing = true)
public class MinuteMetricPublisher {

    /** PutMetricData 한 번에 실을 데이터포인트 수(상한 1,000·1MB 보다 넉넉히 낮게). */
    static final int BATCH = 300;
    /** 분포 한 데이터포인트의 Values/Counts 상한. */
    static final int MAX_VALUES = 150;

    private final MinuteMetricBuffer buffer;
    private final CloudWatchAsyncClient client;
    private final ObjectProvider<MinuteSampler> samplers;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "metric-publisher");
        t.setDaemon(true);
        return t;
    });

    public MinuteMetricPublisher(MinuteMetricBuffer buffer, CloudWatchAsyncClient client,
                                 ObjectProvider<MinuteSampler> samplers) {
        this.buffer = buffer;
        this.client = client;
        this.samplers = samplers;
    }

    @PostConstruct
    void start() {
        long now = System.currentTimeMillis();
        long untilTick = Math.floorMod(5_000L - now, 30_000L);   // 매분 05초·35초에 맞춘다
        executor.scheduleAtFixedRate(this::tick, untilTick, 30_000L, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
        publish();   // 닫히는 태스크의 마지막 끝난 분까지
    }

    private void tick() {
        try {
            samplers.orderedStream().forEach(s -> {
                try { s.sample(); } catch (RuntimeException e) { log.debug("sampler failed {}: {}", s, e.toString()); }
            });
            if (Math.floorMod(System.currentTimeMillis(), 60_000L) < 30_000L) publish();
        } catch (RuntimeException e) {
            log.warn("metric_tick_failed: {}", e.toString());   // 스레드가 죽으면 이후 틱이 전부 멈춘다
        }
    }

    /** 끝난 분을 보낸다 — 직전 분이 확실히 닫힌 뒤(매분 5초)에 부른다. */
    public void publish() {
        List<MinuteMetricBuffer.Datum> data = buffer.drainCompleted();
        if (data.isEmpty()) return;
        for (Map.Entry<String, List<MetricDatum>> ns : toRequests(data).entrySet()) {
            List<MetricDatum> all = ns.getValue();
            for (int i = 0; i < all.size(); i += BATCH) {
                List<MetricDatum> chunk = all.subList(i, Math.min(all.size(), i + BATCH));
                try {
                    client.putMetricData(PutMetricDataRequest.builder().namespace(ns.getKey()).metricData(chunk).build())
                            .get(20, TimeUnit.SECONDS);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    log.warn("metric_publish_failed namespace={} count={}: {}", ns.getKey(), chunk.size(), e.toString());
                }
            }
        }
    }

    /** 네임스페이스별로 묶고, 분포는 150개씩 나눈다. */
    static Map<String, List<MetricDatum>> toRequests(List<MinuteMetricBuffer.Datum> data) {
        Map<String, List<MetricDatum>> byNamespace = new LinkedHashMap<>();
        for (MinuteMetricBuffer.Datum d : data) {
            List<MetricDatum> out = byNamespace.computeIfAbsent(d.key().namespace(), k -> new ArrayList<>());
            MetricDatum.Builder base = MetricDatum.builder()
                    .metricName(d.key().name())
                    .dimensions(d.key().sortedDimensions().entrySet().stream()
                            .map(e -> Dimension.builder().name(e.getKey()).value(e.getValue()).build()).toList())
                    .timestamp(Instant.ofEpochMilli(d.epochMillis()))
                    .unit(unit(d.key().unit()))
                    .storageResolution(60);
            if (d.counts() == null) {
                out.add(base.value(d.value()).build());
                continue;
            }
            List<Map.Entry<Double, Long>> entries = new ArrayList<>(d.counts().entrySet());
            for (int i = 0; i < entries.size(); i += MAX_VALUES) {
                List<Map.Entry<Double, Long>> part = entries.subList(i, Math.min(entries.size(), i + MAX_VALUES));
                out.add(base.copy()
                        .values(part.stream().map(Map.Entry::getKey).toList())
                        .counts(part.stream().map(e -> (double) e.getValue()).toList())
                        .build());
            }
        }
        return byNamespace;
    }

    private static StandardUnit unit(MetricKey.Unit unit) {
        return switch (unit) {
            case COUNT -> StandardUnit.COUNT;
            case MILLISECONDS -> StandardUnit.MILLISECONDS;
            case PERCENT -> StandardUnit.PERCENT;
            case SECONDS -> StandardUnit.SECONDS;
            case NONE -> StandardUnit.NONE;
        };
    }
}
