package com.ruleup.ruleup_backend.auth;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.function.IntSupplier;

/**
 * Refresh Token 정리 배치.
 * 일반 만료·폐기 토큰은 30일, 재사용 탐지 보안 기록은 180일 보관한다(기본값).
 * 대량 DELETE의 장시간 락을 피하려고 작은 트랜잭션 여러 번으로 나눠 처리한다.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenCleanupProperties properties;

    @Scheduled(cron = "${app.auth.refresh-token-cleanup.cron:0 30 4 * * *}", zone = "Asia/Seoul")
    public void cleanupOldTokens() {
        Instant now = Instant.now();
        Instant ordinaryCutoff = now.minus(Duration.ofDays(properties.ordinaryRetentionDays()));
        Instant reuseCutoff = now.minus(Duration.ofDays(properties.reuseDetectedRetentionDays()));
        int batchSize = properties.batchSize();

        DrainResult expired = drain(() -> refreshTokenRepository.deleteExpiredBatch(
                ordinaryCutoff, batchSize));
        DrainResult revoked = drain(() -> refreshTokenRepository.deleteRevokedBatch(
                ordinaryCutoff, batchSize));
        DrainResult reused = drain(() -> refreshTokenRepository.deleteReuseDetectedBatch(
                reuseCutoff, batchSize));

        int ordinaryDeleted = expired.deleted() + revoked.deleted();
        if (ordinaryDeleted > 0 || reused.deleted() > 0) {
            log.info("Refresh Token 정리: 일반 {}건({}일), 재사용 탐지 {}건({}일)",
                    ordinaryDeleted, properties.ordinaryRetentionDays(),
                    reused.deleted(), properties.reuseDetectedRetentionDays());
        }
        if (expired.capped() || revoked.capped() || reused.capped()) {
            log.warn("Refresh Token 정리가 일일 배치 상한에 도달했습니다: batchSize={}, maxBatchesPerType={}",
                    batchSize, properties.maxBatchesPerType());
        }
    }

    /** repository 메서드 한 번이 한 트랜잭션이다. 서비스 전체를 한 트랜잭션으로 묶지 않는다. */
    private DrainResult drain(IntSupplier deleteBatch) {
        int total = 0;
        for (int batch = 0; batch < properties.maxBatchesPerType(); batch++) {
            int deleted = deleteBatch.getAsInt();
            total += deleted;
            if (deleted < properties.batchSize()) return new DrainResult(total, false);
        }
        return new DrainResult(total, true);
    }

    private record DrainResult(int deleted, boolean capped) {}
}
