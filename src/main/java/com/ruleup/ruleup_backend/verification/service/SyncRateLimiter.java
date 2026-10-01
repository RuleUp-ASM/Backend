package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.verification.config.VerificationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    private final Map<String, Long> lastSyncAt = new ConcurrentHashMap<>();
    private final VerificationProperties properties;

    public SyncRateLimiter(VerificationProperties properties) {
        this.properties = properties;
    }

    public void check(String userId, boolean backlog) {
        long minInterval = 1000L * (backlog
                ? properties.syncBacklogMinIntervalSec() : properties.syncMinIntervalSec());
        if (minInterval <= 0) return;   // 제한 없음(설정으로 끈 경우)
        long now = Instant.now().toEpochMilli();
        Long prev = lastSyncAt.put(userId, now);
        if (prev != null && now - prev < minInterval) {
            lastSyncAt.put(userId, prev);   // 거부된 호출은 마지막 시각 갱신 안 함
            // 남은 초를 함께 준다 — 앱의 「동기화」 버튼이 실패 대신 「n초 뒤 다시」를 안내할 수 있게.
            throw BusinessException.rateLimited(ErrorCode.SYNC_TOO_FREQUENT,
                    (minInterval - (now - prev) + 999) / 1000);
        }
        releaseOnRollback(userId, now, prev);
    }

    /**
     * 처리에 실패한 sync 는 간격을 쓰지 않은 것으로 되돌린다. 되돌리지 않으면 500 으로 끝난 요청이
     * 슬롯을 차지해, 사용자가 곧바로 다시 누른 「동기화」가 429 로 튕긴다 — 실패가 두 번 보인다.
     * 그 사이 다른 요청이 시각을 갱신했으면 건드리지 않는다.
     */
    private void releaseOnRollback(String userId, long stamp, Long prev) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                if (prev == null) lastSyncAt.remove(userId, stamp);
                else lastSyncAt.replace(userId, stamp, prev);
            }
        });
    }
}
