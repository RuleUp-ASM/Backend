package com.ruleup.ruleup_backend.common.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 발행 대기함의 <b>잔여 건수와 최장 지연</b> (백엔드 4-5 「정정 전파 재처리 — 잔여 건수·최장 지연」).
 *
 * <p>재시도가 「성공할 때까지」 도는지를 확인하려면 두 값이 필요하다. 건수만 보면 <b>한 건이
 * 며칠째 못 나가고 있는 상태</b>를 놓치고, 지연만 보면 규모를 모른다. 스펙이 둘을 함께 요구한
 * 이유다.
 *
 * <p>스크레이프마다 DB 를 세지 않고 <b>1분마다 미리 계산</b>해 둔다. 게이지를 조회 시점에
 * 질의로 채우면 모니터링이 늘어날수록 그 비용이 곱해진다.
 */
@Slf4j
@Component
public class OutboxMetrics {

    private final OutboxRepository repository;

    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong pendingOldestAgeSeconds = new AtomicLong();
    private final AtomicLong deadLetteredCount = new AtomicLong();
    private final AtomicLong deadLetteredOldestAgeSeconds = new AtomicLong();

    /** 게이지는 <b>미리 계산한 값</b>을 읽기만 한다. */
    public OutboxMetrics(OutboxRepository repository, MeterRegistry registry) {
        this.repository = repository;
        registry.gauge("outbox.pending.count", pendingCount, AtomicLong::doubleValue);
        registry.gauge("outbox.pending.oldest_age_seconds", pendingOldestAgeSeconds, AtomicLong::doubleValue);
        registry.gauge("outbox.dead_lettered.count", deadLetteredCount, AtomicLong::doubleValue);
        registry.gauge("outbox.dead_lettered.oldest_age_seconds", deadLetteredOldestAgeSeconds,
                AtomicLong::doubleValue);
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            Instant now = Instant.now();
            pendingCount.set(repository.countByProcessedAtIsNullAndDeadLetteredAtIsNull());
            pendingOldestAgeSeconds.set(ageSeconds(repository.oldestPendingCreatedAt(), now));
            deadLetteredCount.set(repository.countByDeadLetteredAtIsNotNull());
            deadLetteredOldestAgeSeconds.set(ageSeconds(repository.oldestDeadLetteredAt(), now));
        } catch (RuntimeException e) {
            // 지표 갱신이 실패해도 발행은 계속돼야 한다. 다음 주기가 다시 센다.
            log.warn("아웃박스 지표 갱신 실패: {}", e.toString());
        }
    }

    private static long ageSeconds(Instant at, Instant now) {
        return (at == null) ? 0L : Math.max(Duration.between(at, now).getSeconds(), 0L);
    }
}
