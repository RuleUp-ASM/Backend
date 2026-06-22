package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sync 최소 간격 강제 (§2.8). in-memory(단일 인스턴스 전제, UploadRateLimiter 패턴).
 * 같은 유저가 MIN_INTERVAL 안에 또 sync하면 429 SYNC_TOO_FREQUENT.
 * (스케일 시 Redis로 이전 — 그땐 멱등 키도 분산 저장)
 */
@Component
public class SyncRateLimiter {

    private static final long MIN_INTERVAL_MILLIS = 5 * 60_000L;   // 5분

    private final Map<String, Long> lastSyncAt = new ConcurrentHashMap<>();

    public void check(String userId) {
        long now = Instant.now().toEpochMilli();
        Long prev = lastSyncAt.put(userId, now);
        if (prev != null && now - prev < MIN_INTERVAL_MILLIS) {
            lastSyncAt.put(userId, prev);   // 거부된 호출은 마지막 시각 갱신 안 함
            throw new BusinessException(ErrorCode.SYNC_TOO_FREQUENT);
        }
    }
}
