package com.ruleup.ruleup_backend.observability.ops;

import com.ruleup.ruleup_backend.config.runtime.RuntimeRole;
import com.ruleup.ruleup_backend.config.runtime.RuntimeRoleProperties;
import com.ruleup.ruleup_backend.observability.metric.MetricContext;
import com.ruleup.ruleup_backend.observability.metric.MinuteMetricBuffer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 대시보드 OPS-6(JVM 런타임) 지표 — 이름·차원이 대시보드와 어긋나면 위젯이 빈 칸이 된다. */
class RuntimeOpsSamplerJvmPanelTest {

    @Test
    @DisplayName("메모리 풀 이름을 대시보드 영역으로 묶고, 모르는 풀은 내보내지 않는다")
    void memoryArea() {
        assertThat(RuntimeOpsSampler.memoryArea("G1 Eden Space")).isEqualTo("eden");
        assertThat(RuntimeOpsSampler.memoryArea("G1 Survivor Space")).isEqualTo("survivor");
        assertThat(RuntimeOpsSampler.memoryArea("G1 Old Gen")).isEqualTo("old");
        assertThat(RuntimeOpsSampler.memoryArea("Metaspace")).isEqualTo("metaspace");
        assertThat(RuntimeOpsSampler.memoryArea("CodeHeap 'non-nmethods'")).isEqualTo("codecache");
        assertThat(RuntimeOpsSampler.memoryArea("Compressed Class Space")).isNull();
    }

    @Test
    @DisplayName("한 번 채집하면 힙 영역·스레드 상태·클래스·업타임이 RuleUp/Ops 로 쌓이고, GC 횟수는 두 번째부터 증가분으로 낸다")
    @SuppressWarnings("unchecked")
    void samplesPanelMetrics() {
        MutableClock clock = new MutableClock();
        MinuteMetricBuffer buffer = new MinuteMetricBuffer(clock);
        RuntimeOpsSampler sampler = new RuntimeOpsSampler(buffer, new MetricContext("stg", new RuntimeRoleProperties(RuntimeRole.API, false)),
                mock(ObjectProvider.class), new SimpleMeterRegistry(), mock(ApplicationContext.class));

        sampler.sample();
        sampler.sample();
        clock.now = clock.now.plusSeconds(60);
        var data = buffer.drainCompleted();

        Set<String> names = data.stream().map(d -> d.key().name()).collect(Collectors.toSet());
        assertThat(names).contains("JvmMemoryUsedMb", "JvmHeapMaxMb", "JvmGcPauseCount", "JvmThreads", "JvmClassesLoaded", "ProcessUptimeSec");
        Set<String> states = data.stream().filter(d -> d.key().name().equals("JvmThreads"))
                .map(d -> d.key().dimensions().get("State")).collect(Collectors.toSet());
        assertThat(states).containsExactlyInAnyOrder("live", "daemon", "peak", "runnable", "blocked", "waiting");
        Map<String, String> heapDims = data.stream().filter(d -> d.key().name().equals("JvmMemoryUsedMb")).findFirst().orElseThrow().key().dimensions();
        assertThat(heapDims).containsEntry("Env", "stg").containsKey("Area");
        assertThat(data).allSatisfy(d -> assertThat(d.key().namespace()).isEqualTo(OpsSignals.OPS));
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-15T10:00:05Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
