package com.ruleup.ruleup_backend.challenge.stats;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meterRegistry;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefreshRequested(ChallengeStatsRefreshRequested event) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            projectionService.refresh(event.challengeId());
        } catch (Exception e) {
            Counter.builder("challenge_stats_refresh_failure_total")
                    .description("challenge_stats 비동기 갱신 실패 횟수")
                    .register(meterRegistry)
                    .increment();
            log.error("challenge_stats_refresh_failure challengeId={} reason={}: {}",
                    event.challengeId(), event.reason(), e.getMessage(), e);
        } finally {
            sample.stop(Timer.builder("stats_refresh_duration")
                    .description("챌린지 통계 Projection 재계산 시간")
                    .tag("reason", event.reason())
                    .register(meterRegistry));
        }
    }
}
