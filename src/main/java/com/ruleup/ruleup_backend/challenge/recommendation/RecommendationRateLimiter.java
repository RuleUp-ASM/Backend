package com.ruleup.ruleup_backend.challenge.recommendation;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 챌린지 추천(LLM) 호출 사전 차단 — Step0 (생성 및 라이프사이클 §3-2).
 * 사용자당 1분 10회. 초과 시 429 RECOMMENDATION_RATE_LIMITED + retryAfterSeconds(응답 reason).
 * EC2 단일 인스턴스 기준 인메모리(UploadRateLimiter 와 동일 패턴).
 */
@Component
public class RecommendationRateLimiter {

    private static final int MAX_PER_MINUTE = 10;
    private static final long WINDOW_MILLIS = 60_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void check(String userId) {
        long now = Instant.now().toEpochMilli();
        windows.values().removeIf(w -> now - w.startMillis >= WINDOW_MILLIS);
        Window w = windows.compute(userId, (key, old) -> {
            if (old == null || now - old.startMillis >= WINDOW_MILLIS) {
                return new Window(now, 1);
            }
            old.count++;
            return old;
        });
        if (w.count > MAX_PER_MINUTE) {
            long retryAfterSeconds = Math.max(1, (WINDOW_MILLIS - (now - w.startMillis) + 999) / 1000);
            throw new BusinessException(ErrorCode.RECOMMENDATION_RATE_LIMITED, String.valueOf(retryAfterSeconds));
        }
    }

    private static class Window {
        long startMillis;
        int count;
        Window(long startMillis, int count) { this.startMillis = startMillis; this.count = count; }
    }
}
