package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sync 최소 간격 강제. in-memory(단일 인스턴스 전제, UploadRateLimiter 패턴).
 * 같은 유저가 최소 간격 안에 또 sync하면 429 SYNC_TOO_FREQUENT.
 *
 * <p><b>복구 전송(backlog=true)에는 별도 허용치</b>를 적용한다. 장기 오프라인에서 돌아온 클라는
 * 밀린 구간을 여러 번 나눠 올려야 하는데, 평상시 간격을 그대로 적용하면 복구 자체가 막힌다.
 *
 * <p>(스케일 시 Redis로 이전 — 그땐 멱등 키도 분산 저장)
 */
@Component
public class SyncRateLimiter {

    private static final long MIN_INTERVAL_MILLIS = 5 * 60_000L;           // 평상시 5분
    private static final long BACKLOG_MIN_INTERVAL_MILLIS = 10_000L;       // 복구 전송 10초

    private final Map<String, Long> lastSyncAt = new ConcurrentHashMap<>();

    public void check(String userId, boolean backlog) {
        long now = Instant.now().toEpochMilli();
        long minInterval = backlog ? BACKLOG_MIN_INTERVAL_MILLIS : MIN_INTERVAL_MILLIS;
        Long prev = lastSyncAt.put(userId, now);
        if (prev != null && now - prev < minInterval) {
            lastSyncAt.put(userId, prev);   // 거부된 호출은 마지막 시각 갱신 안 함
            throw new BusinessException(ErrorCode.SYNC_TOO_FREQUENT);
        }
    }
}
