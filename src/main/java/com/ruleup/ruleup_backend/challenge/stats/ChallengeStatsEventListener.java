package com.ruleup.ruleup_backend.challenge.stats;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 통계 재계산 이벤트 수신 (탐색 백엔드 테크스펙 §6-5).
 *
 * <p>원본 트랜잭션이 커밋된 뒤에만 실행한다. 실패는 <b>절대 원본으로 전파하지 않는다</b> —
 * 이미 성공한 가입·탈퇴를 통계 때문에 되돌릴 이유가 없기 때문이다. 대신 로그를 남기고
 * 다음 이벤트 또는 일 1회 reconciliation 이 복구한다.
 */
@Component
@RequiredArgsConstructor
public class ChallengeStatsEventListener {

    private static final Logger log = LoggerFactory.getLogger(ChallengeStatsEventListener.class);

    private final ChallengeStatsProjectionService projectionService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefreshRequested(ChallengeStatsRefreshRequested event) {
        try {
            projectionService.refresh(event.challengeId());
        } catch (Exception e) {
            log.error("challenge_stats_refresh_failure challengeId={} reason={}: {}",
                    event.challengeId(), event.reason(), e.getMessage(), e);
        }
    }
}
