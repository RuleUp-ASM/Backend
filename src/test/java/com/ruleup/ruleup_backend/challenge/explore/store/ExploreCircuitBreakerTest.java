package com.ruleup.ruleup_backend.challenge.explore.store;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ExploreCircuitBreakerTest {

    @Test
    @DisplayName("기동 워밍업 실패로 강제로 연 회로도, 닫힌 뒤 첫 성공에서 「복귀」를 남긴다")
    void forcedOpenLogsRecovery(CapturedOutput output) {
        ExploreCircuitBreaker circuit = new ExploreCircuitBreaker(new SimpleMeterRegistry());
        ReflectionTestUtils.setField(circuit, "enabled", true);
        ReflectionTestUtils.setField(circuit, "openDurationMs", 0L);

        circuit.openManually("워밍업 실패(기동): RedisConnectionFailureException");
        assertThat(circuit.isOpen()).as("열림 시간이 지나면 반열림으로 닫힌다").isFalse();
        circuit.callQuietly(() -> { });

        assertThat(output).contains("탐색 Redis 복귀 — 폴백 해제");
    }
}
