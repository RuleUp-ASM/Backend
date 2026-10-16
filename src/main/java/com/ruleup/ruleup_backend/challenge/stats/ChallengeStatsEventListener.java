package com.ruleup.ruleup_backend.challenge.stats;

import com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 통계 재계산 이벤트 수신 (탐색 백엔드 테크스펙 §6-5).
 *
 * <p>원본 트랜잭션이 커밋된 뒤에만 실행한다. 실패는 <b>절대 원본으로 전파하지 않는다</b> —
 * 이미 성공한 가입·탈퇴를 통계 때문에 되돌릴 이유가 없기 때문이다. 대신 로그를 남기고
 * 다음 이벤트 또는 일 1회 reconciliation 이 복구한다.
 *
 * <p><b>같은 방은 한 번에 하나만 돌리고, 도는 동안 들어온 요청은 끝난 뒤 한 번으로 합친다.</b> 재계산은 방 행을
 * 잠그고 원천을 다시 세므로, 끝난 뒤의 한 번이 그사이 커밋을 모두 본다. 예전에는 이벤트마다 공용 비동기
 * 실행기(8스레드·대기열 무제한)에 따로 쌓여, 인기 방 가입·확정이 몰리면 같은 방 재계산 여럿이 커넥션을 쥔 채
 * 방 행 잠금을 줄 서 기다렸다. 전용 스레드 4개로 따로 돌려 다른 비동기 작업과 커넥션을 다투지 않게 한다.
 *
 * <p><b>탐색 파생 인덱스도 여기서 밀어 넣는다.</b> 순서가 중요하다 — {@code challenge_stats} 를
 * 먼저 갱신하고 그 결과를 Redis 로 투영해야 두 저장소가 같은 값을 본다. 반대로 두면 Redis 가
 * 직전 통계로 정렬돼 SQL 폴백과 순서가 어긋난다.
 */
@Component
public class ChallengeStatsEventListener {

    private static final Logger log = LoggerFactory.getLogger(ChallengeStatsEventListener.class);

    private final ChallengeStatsProjectionService projectionService;
    private final ExploreIndexer exploreIndexer;
    private final MeterRegistry meterRegistry;
    /** 재계산 중이거나 대기 중인 방 → 그사이 다시 요청이 왔는가(한 번 더 돌려야 하는가). */
    private final Map<UUID, Boolean> pending = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, Thread.ofPlatform().name("stats-refresh-", 0).daemon().factory());

    public ChallengeStatsEventListener(ChallengeStatsProjectionService projectionService, ExploreIndexer exploreIndexer,
                                       MeterRegistry meterRegistry) {
        this.projectionService = projectionService;
        this.exploreIndexer = exploreIndexer;
        this.meterRegistry = meterRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefreshRequested(ChallengeStatsRefreshRequested event) {
        boolean[] start = {false};
        pending.compute(event.challengeId(), (id, again) -> {
            if (again == null) { start[0] = true; return Boolean.FALSE; }
            return Boolean.TRUE;   // 이미 돌고 있다 — 끝난 뒤 한 번 더
        });
        if (start[0]) executor.execute(() -> drain(event));
        else meterRegistry.counter("challenge_stats_refresh_coalesced_total").increment();
    }

    private void drain(ChallengeStatsRefreshRequested event) {
        while (true) {
            refresh(event);
            // 그사이 요청이 왔으면 표시를 지우고 한 번 더, 아니면 빠진다(없애는 것과 확인이 한 번에 일어난다).
            if (pending.computeIfPresent(event.challengeId(), (id, again) -> again ? Boolean.FALSE : null) == null) return;
        }
    }

    private void refresh(ChallengeStatsRefreshRequested event) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            projectionService.refresh(event.challengeId());
            // 파생 인덱스 투영. 내부에서 회로차단기가 감싸므로 Redis 가 죽어 있어도 여기서 터지지 않는다.
            exploreIndexer.index(event.challengeId());
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

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
