package com.ruleup.ruleup_backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Refresh Token 보관 및 일일 정리 배치 설정. */
@ConfigurationProperties(prefix = "app.auth.refresh-token-cleanup")
public record RefreshTokenCleanupProperties(
        Integer ordinaryRetentionDays,
        Integer reuseDetectedRetentionDays,
        Integer batchSize,
        Integer maxBatchesPerType
) {
    private static final int DEFAULT_ORDINARY_RETENTION_DAYS = 30;
    private static final int DEFAULT_REUSE_DETECTED_RETENTION_DAYS = 180;
    private static final int DEFAULT_BATCH_SIZE = 1_000;
    private static final int DEFAULT_MAX_BATCHES_PER_TYPE = 100;

    public RefreshTokenCleanupProperties {
        ordinaryRetentionDays = positiveOrDefault(
                ordinaryRetentionDays, DEFAULT_ORDINARY_RETENTION_DAYS);
        reuseDetectedRetentionDays = positiveOrDefault(
                reuseDetectedRetentionDays, DEFAULT_REUSE_DETECTED_RETENTION_DAYS);
        batchSize = positiveOrDefault(batchSize, DEFAULT_BATCH_SIZE);
        maxBatchesPerType = positiveOrDefault(maxBatchesPerType, DEFAULT_MAX_BATCHES_PER_TYPE);
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }
}
