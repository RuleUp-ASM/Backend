package com.ruleup.ruleup_backend.challenge.explore.store;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 파생 인덱스의 세 갱신 층 (탐색 테크스펙 5-1 "갱신은 3층 모두 push").
 *
 * <table>
 *   <tr><th>층</th><th>주기</th><th>맡는 것</th></tr>
 *   <tr><td>이벤트 즉시</td><td>COMMIT 직후</td><td>참여·탈퇴·판정 확정 — <b>인기 상승은 여기서 즉시</b></td></tr>
 *   <tr><td>스윕</td><td>5분</td><td>24시간 창을 벗어난 참여 제거 — <b>인기 하락</b></td></tr>
 *   <tr><td>대조</td><td>매일 03:30</td><td>유실·버그 보정 + 원천에 없는 유령 제거</td></tr>
 * </table>
 *
 * <p>하락만 지연을 허용하는 이유는 비대칭이 사용자에게 보이는 방향이 다르기 때문이다 — 방금 참여한
 * 방이 인기에 안 뜨면 즉시 이상해 보이지만, 어제 몰렸던 방이 5분 늦게 내려가는 것은 티가 나지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExploreIndexJobs {

    private final ExploreIndexer indexer;
    private final ExploreRedisStore store;
    private final ExploreCircuitBreaker circuit;

    /**
     * 기동 워밍업. <b>플래그가 없을 때만</b> 전체를 만든다 — 인스턴스가 늘 때마다 전수 재구성이
     * 돌면 배포가 곧 부하가 된다. Redis 가 없는 환경에서는 여기서 회로가 열리고 SQL 경로로 간다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        if (circuit.isOpen()) {
            log.info("탐색 Redis 비활성 또는 회로 OPEN — 워밍업을 건너뛰고 MySQL 경로로 돈다");
            return;
        }
        try {
            if (store.isWarmed()) return;
            indexer.reindexAll();
        } catch (RuntimeException e) {
            circuit.openManually("워밍업 실패: " + e);
        }
    }

    /**
     * 5분 스윕 — 24시간 창을 벗어난 참여를 걷어내 인기 점수를 내린다.
     *
     * <p>후보 전체를 다시 투영한다. 방 수가 만 단위가 되기 전까지는 "무엇이 내려갔는지" 를
     * 따로 추적하는 것보다 전수 재투영이 단순하고 틀릴 여지가 적다.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void sweepPopularityDecay() {
        if (circuit.isOpen()) return;
        List<UUID> ids = indexer.visibleCandidateIds();
        for (UUID id : ids) indexer.index(id);
        log.debug("explore_sweep candidates={}", ids.size());
    }

    /**
     * 매일 03:30 대조 — 점검 창(02:00~03:00)과 00시 판정 배치를 피한다.
     * 유령 제거는 이 배치만 할 수 있다(증분 갱신은 "원천에 없는 행"을 발견하지 못한다).
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    public void reconcile() {
        if (circuit.isOpen()) return;
        try {
            indexer.reindexAll();
        } catch (RuntimeException e) {
            log.error("탐색 인덱스 대조 실패 — 다음 회차가 다시 시도한다: {}", e.toString());
        }
    }
}
