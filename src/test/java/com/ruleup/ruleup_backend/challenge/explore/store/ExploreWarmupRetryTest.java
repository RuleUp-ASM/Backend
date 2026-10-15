package com.ruleup.ruleup_backend.challenge.explore.store;

import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsReconciliationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 배포 직후 첫 Redis 연결 실패가 곧바로 5분 폴백이 되지 않게 — 부하테스트 2026-10. */
class ExploreWarmupRetryTest {

    @Test
    @DisplayName("첫 연결이 실패해도 다시 붙으면 회로를 열지 않는다")
    void retriesFirstConnection() {
        ExploreRedisStore store = mock(ExploreRedisStore.class);
        doThrow(new RedisConnectionFailureException("Unable to connect to Redis", new IllegalStateException("handshake timed out")))
                .doNothing().when(store).ping();
        ExploreCircuitBreaker circuit = new ExploreCircuitBreaker(new SimpleMeterRegistry());
        org.springframework.test.util.ReflectionTestUtils.setField(circuit, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(circuit, "openDurationMs", 30_000L);
        ExploreIndexJobs jobs = new ExploreIndexJobs(mock(ExploreIndexer.class), store, circuit, mock(ChallengeStatsReconciliationService.class));

        assertThat(jobs.redisReachable()).isTrue();
        verify(store, times(2)).ping();
        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    @DisplayName("실패 원인은 감싼 예외가 아니라 맨 안쪽 원인까지 남긴다")
    void logsRootCause() {
        var e = new RedisConnectionFailureException("Unable to connect to Redis", new IllegalStateException("handshake timed out"));
        assertThat(ExploreIndexJobs.rootCause(e)).contains("RedisConnectionFailureException").contains("handshake timed out");
    }
}
