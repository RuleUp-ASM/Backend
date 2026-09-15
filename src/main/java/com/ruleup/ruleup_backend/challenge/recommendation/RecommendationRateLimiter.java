package com.ruleup.ruleup_backend.challenge.recommendation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 사용자별 60초 fixed window. Redis 장애 동안만 Caffeine으로 제한한다. */
@Component
public class RecommendationRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RecommendationRateLimiter.class);
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], 60) end
            return {count, redis.call('TTL', KEYS[1])}
            """, List.class);
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1)).build();
    private volatile long retryRedisAt;

    public RecommendationRateLimiter(StringRedisTemplate redis, MeterRegistry metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    public void check(String userId) {
        long now = System.nanoTime();
        if (retryRedisAt == 0 || now - retryRedisAt >= 0) {
            List<?> result = null;
            try {
                result = redis.execute(SCRIPT, List.of("rate:challenge:draft:" + userId));
                if (result == null || result.size() != 2
                        || !(result.get(0) instanceof Number) || !(result.get(1) instanceof Number)) throw new IllegalStateException("Empty rate limit result");
            } catch (RuntimeException unavailable) {
                result = null;
                retryRedisAt = now + Duration.ofSeconds(10).toNanos();
                metrics.counter("challenge.draft.rate_limiter.fallback").increment();
                log.warn("draft_rate_limit_fallback reason={}", unavailable.getClass().getSimpleName());
            }
            if (result != null) {
                rejectIfLimited(((Number) result.get(0)).longValue(), ((Number) result.get(1)).longValue());
                return;
            }
        }
        Window window = windows.get(userId, key -> new Window(now));
        synchronized (window) {
            rejectIfLimited(++window.count, Math.max(1,
                    60 - Duration.ofNanos(now - window.startedAt).toSeconds()));
        }
    }

    private static void rejectIfLimited(long count, long ttl) {
        if (count > 10) throw new BusinessException(ErrorCode.RECOMMENDATION_RATE_LIMITED,
                String.valueOf(Math.max(1, ttl)));
    }

    private static class Window {
        final long startedAt;
        int count;
        Window(long startedAt) { this.startedAt = startedAt; }
    }
}
