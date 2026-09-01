package com.ruleup.ruleup_backend.challenge.explore.store;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 탐색 Redis 회로차단기 (탐색 테크스펙 5-5-3).
 *
 * <h4>왜 필요한가</h4>
 * Redis 를 정렬 원천으로 삼는 순간 <b>Redis 가용성이 곧 탐색 API 가용성</b>이 된다. 그래서
 * 폴백은 선택이 아니라 전제다. 다만 매 요청이 타임아웃까지 기다렸다 폴백하면 Redis 가 죽은 동안
 * 탐색이 통째로 느려진다 — 한 번 실패를 기억해 <b>일정 시간은 묻지도 않고</b> SQL 로 간다.
 *
 * <h4>조용히 느려지는 것이 가장 나쁘다</h4>
 * 그래서 폴백 진입·복귀를 반드시 로그와 게이지로 남긴다. Redis 가 없는 환경(로컬·CI·Redis
 * 미배포 스테이지)에서는 첫 요청에 회로가 열리고 그대로 SQL 경로로 도는 것이 정상 동작이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExploreCircuitBreaker {

    /** 이 횟수만큼 연속 실패하면 연다. 1회 실패로 열면 순간적인 네트워크 흔들림에 과민해진다. */
    private static final int FAILURE_THRESHOLD = 3;

    private final MeterRegistry meterRegistry;

    @Value("${app.explore.redis.enabled:true}")
    private boolean enabled;

    @Value("${app.explore.redis.open-duration-ms:30000}")
    private long openDurationMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 이 시각까지는 Redis 를 건드리지 않는다. null 이면 닫힌 상태. */
    private final AtomicReference<Instant> openUntil = new AtomicReference<>();

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("explore_redis_circuit_open", this, b -> b.isOpen() ? 1d : 0d)
                .description("탐색 Redis 회로차단기 OPEN 여부 — 1이면 MySQL 폴백 경로")
                .register(meterRegistry);
    }

    public boolean isOpen() {
        if (!enabled) return true;
        Instant until = openUntil.get();
        if (until == null) return false;
        if (Instant.now().isBefore(until)) return true;
        // 반열림 — 한 번 시도해 보고 성공하면 닫힌다.
        openUntil.compareAndSet(until, null);
        return false;
    }

    /**
     * Redis 호출을 감싼다. 실패하면 회로를 세고 {@code fallback} 을 준다.
     *
     * <p>회로가 열려 있으면 <b>Redis 를 아예 부르지 않고</b> 곧바로 폴백이다.
     */
    public <T> T callOrFallback(Supplier<T> redisCall, Supplier<T> fallback) {
        if (isOpen()) return fallback.get();
        try {
            T result = redisCall.get();
            onSuccess();
            return result;
        } catch (RuntimeException e) {
            onFailure(e);
            return fallback.get();
        }
    }

    /** 쓰기 경로용 — 실패해도 도메인은 그대로 두고 로그만 남긴다(가입이 Redis 때문에 실패하면 안 된다). */
    public void callQuietly(Runnable redisCall) {
        if (isOpen()) return;
        try {
            redisCall.run();
            onSuccess();
        } catch (RuntimeException e) {
            onFailure(e);
        }
    }

    /**
     * 밖에서 잡은 Redis 실패를 회로에 알린다 — 호출부가 폴백을 직접 처리해야 하는 경로용
     * (커서를 들고 있으면 그냥 폴백할 수 없고 {@code CURSOR_INVALID} 를 내야 한다).
     */
    public void recordFailure(RuntimeException e) {
        onFailure(e);
    }

    private void onSuccess() {
        if (consecutiveFailures.getAndSet(0) >= FAILURE_THRESHOLD) {
            log.info("탐색 Redis 복귀 — 폴백 해제");
        }
    }

    private void onFailure(RuntimeException e) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD && openUntil.get() == null) {
            openUntil.set(Instant.now().plus(Duration.ofMillis(openDurationMs)));
            log.warn("탐색 Redis 폴백 진입 — {}ms 동안 MySQL 경로로 돈다. 원인: {}",
                    openDurationMs, e.toString());
        } else {
            log.debug("탐색 Redis 실패 {}회: {}", failures, e.toString());
        }
    }

    /** 워밍업이 끝나기 전에는 조회를 보내지 않기 위해 강제로 연다. */
    public void openManually(String reason) {
        openUntil.set(Instant.now().plus(Duration.ofMillis(openDurationMs)));
        log.warn("탐색 Redis 폴백 강제 진입 — {}", reason);
    }
}
