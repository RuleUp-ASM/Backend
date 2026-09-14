package com.ruleup.ruleup_backend.challenge.explore.store;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Instant;
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
     * 원천 재계산. 03:30 대조가 <b>먼저</b> 부른다 — 04:40 에 따로 돌던 것을 앞으로 당긴 것이
     * 아니라, 대조가 독립적으로 복구할 수 있으려면 같은 회차 안에서 원천을 다시 봐야 한다.
     */
    private final com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsReconciliationService
            statsReconciliation;

    /**
     * 기동 워밍업. <b>플래그가 없을 때만</b> 전체를 만든다 — 인스턴스가 늘 때마다 전수 재구성이
     * 돌면 배포가 곧 부하가 된다. Redis 가 없는 환경에서는 여기서 회로가 열리고 SQL 경로로 간다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        ensureWarmed("기동");
    }

    /**
     * 준비 상태를 확인하고, 아니면 원천으로 만든다.
     *
     * <p>기동 때 한 번만 부르면 안 된다 — 그때 Redis 가 죽어 있었으면 <b>다음 03:30 까지</b>
     * 탐색이 계속 503 이다. 5분 스윕이 매번 이 확인을 거치므로, 복구되면 그 회차에 살아난다.
     * Primary 승격으로 파생이 통째로 비어 돌아온 경우도 같은 자리에서 걸린다.
     *
     * <p>{@code ex:ready} 가 있어도 그것만으로 최신을 보장하지 않는다(백엔드 8-2). 그래서
     * 준비돼 있으면 여기서는 손대지 않고, 값의 정합은 5분 스윕과 03:30 대조가 맡는다.
     */
    private boolean ensureWarmed(String reason) {
        if (circuit.isOpen()) return false;
        try {
            // 플래그만 믿지 않는다. 승격 직후 복제가 덜 따라오면 ready 와 계산 시각은 남고
            // 후보만 일부 빈 채로 돌아오는데, 그 상태는 「정상적인 빈 목록」으로 200 을 낸다.
            //
            // 다만 <b>값 대조는 방마다 왕복</b>이라 구조 대조와 같은 주기로 돌릴 수 없다.
            // 구조 대조는 왕복 수가 키 수(집합 15 · ZSET 19)로 고정이라 방이 늘어도 늘지 않는다.
            // <b>다만 전송량은 그렇지 않다</b> — SMEMBERS·ZRANGE 는 멤버를 전부 실어 오므로
            // 방 수에 비례한다. 10만 방에서 이 경로가 5분 예산 안에 드는지는 아직 실측 전이고,
            // 병목 후보로 봐야 한다(백엔드 11-4). 값 대조는 거기에 방마다 왕복까지 더해지므로
            // {@link #DEEP_VERIFY_INTERVAL} 마다만 돈다.
            if (store.isWarmed()) {
                boolean deep = store.lastDeepVerifyAt()
                        .map(at -> at.isBefore(Instant.now().minus(DEEP_VERIFY_INTERVAL)))
                        .orElse(true);
                if (indexer.projectionMatchesSource(deep)) {
                    if (deep) store.markDeepVerified(Instant.now());
                    return true;
                }

                // <b>첫 어긋남을 손상으로 확정하지 않는다.</b> 준비 상태를 내리는 순간 탐색은
                // 503 이고, 전수 재구성은 그보다 훨씬 비싸다 — 정상 지연을 그렇게 갚으면
                // 대조가 고치는 것보다 많이 망가뜨린다. 먼저 <b>증분 보정을 실제로 돌려</b>
                // 아직 안 도착한 갱신을 도착시키고, 그러고도 어긋나면 그때 손상으로 본다.
                // 재시도는 여기 한 번뿐이라 비용이 회차당 두 번의 대조로 묶인다.
                log.warn("탐색 투영이 원천과 다르다 — 증분 보정 뒤 한 번 더 확인한다");
                indexer.pruneStaleCandidates();
                indexer.reprojectChanged(CONFIRM_WINDOW);
                if (indexer.projectionMatchesSource(deep)) {
                    if (deep) store.markDeepVerified(Instant.now());
                    return true;
                }
                log.error("증분 보정 뒤에도 원천과 맞지 않는다 — 준비 상태를 내리고 다시 만든다");
                store.clearWarmed();
            }
            // 여러 인스턴스가 동시에 기동하거나 동시에 승격을 감지하면 전수 재구성이 겹친다.
            String token = store.tryLockRebuild(REBUILD_LOCK_TTL);
            if (token == null) return false;
            try {
                // 작업 표를 그대로 옮기지 않는다 — 그 값이 틀어져 있으면 틀린 채로 준비 완료가 된다.
                // 통계를 <b>먼저</b> 맞춘다. 실패하면 인덱스를 만들지도 않는다 — 만들어 놓고
                // 나중에 준비 상태를 내리면 그 사이 틀린 값이 정상 응답으로 나간다.
                var stats = statsReconciliation.run();
                if (stats.failed() > 0) {
                    log.error("워밍업 보류 — 통계 재계산 실패 {}건. 다음 회차가 다시 시도한다", stats.failed());
                    return false;
                }
                indexer.reindexAll();   // 끝나면 준비 완료까지 함께 올린다
                store.countRebuild();
                log.info("탐색 인덱스 워밍업 완료 reason={}", reason);
                return true;            // 방금 원천으로 다시 만들었다 — 유령이 있을 수 없다
            } finally {
                store.unlockRebuild(token);
            }
        } catch (RuntimeException e) {
            circuit.openManually("워밍업 실패(" + reason + "): " + e);
            return false;
        }
    }

    /**
     * 5분 보정 — 24시간 창 이탈, 유실된 통계 이벤트, 후보에서 빠진 방을 <b>증분으로</b> 따라잡는다.
     *
     * <p>매 회차 전체를 다시 계산하지 않는다. 마지막 성공 시각을 남겨 그 뒤에 움직인 방만 본다 —
     * 방이 만 단위가 되면 전수 재계산이 5분 안에 끝나지 않고, 그때부터 인기 하락이 밀리기 시작한다.
     * 다만 <b>겹쳐서</b> 읽는다(OVERLAP): 커서를 정확히 이어 붙이면 경계에 걸친 변경이 새 나간다.
     *
     * <p>인기 점수는 「원천이 움직인 방」만으로 좁혀지지 않는다 — 24시간 창은 시간이 지나기만 해도
     * 값이 바뀐다. 그렇다고 후보 전체를 다시 읽을 필요는 없다: 점수가 <b>내려갈 수 있는</b> 방은
     * 지금 창에 가입이 있는 방뿐이고, 그 목록은 인기 ZSET 자신이 알고 있다(가입 0건인 방은 0 그대로).
     *
     * <p>여러 인스턴스가 같은 일을 겹쳐 하지 않도록 잠금을 잡는다. 못 잡으면 그 회차는 건너뛴다 —
     * 다음 5분에 다시 온다.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void sweepPopularityDecay() {
        if (circuit.isOpen()) return;

        // <b>잠금을 먼저 잡는다.</b> 예전에는 정합성 확인이 잠금 밖에 있어서, 인스턴스가 열이면
        // 열 대가 5분마다 같은 전수 대조를 나란히 돌렸다 — 한 대가 하면 되는 일이다.
        String token = store.tryLockSweep(SWEEP_LOCK_TTL);
        if (token == null) {
            log.debug("탐색 보정 건너뜀 — 다른 인스턴스가 수행 중이다");
            return;
        }
        Instant startedAt = Instant.now();
        try {
            // 기동 때 Redis 가 죽어 있었거나 승격으로 파생이 비어 돌아왔다면 여기서 살린다.
            // 이 확인이 없으면 한 번의 실패가 다음 03:30 까지 이어지는 503 이 된다.
            boolean consistent = ensureWarmed("5분 보정");
            if (circuit.isOpen()) return;
            // 아직 준비되지 않았다면 재구성이 실패했다는 뜻이다 — 반쯤 찬 인덱스에 덧칠하지 않는다.
            if (!store.isWarmed()) {
                log.info("탐색 보정 건너뜀 — 재구성이 끝나기를 기다린다");
                return;
            }
            // 후보에서 <b>빠진</b> 방을 걷어낸다. 종료·비공개 전환 이벤트가 유실되면 증분
            // 갱신만으로는 그 방이 목록에 영영 남는다 — 사라진 것은 스윕만 볼 수 있다.
            //
            // 방금 구조 대조가 통과했다면 <b>건너뛴다.</b> 유령이란 「원천에 없는데 파생에 있는
            // 멤버」이고, 그 대조가 본 것이 정확히 그 조건이다 — 통과했다는 말은 유령이 없다는
            // 말이다. 여기서 또 훑으면 같은 회차에 같은 집합을 두 번 내려받는다.
            if (!consistent) indexer.pruneStaleCandidates();

            // 유실된 통계 이벤트를 원천에서 따라잡는다. 마지막 성공 시각이 없으면(첫 회차·
            // Redis 초기화) 전체를 본다 — 그 한 번은 느려도 정합이 우선이다.
            // <b>시각이 아니라 간격</b>을 넘긴다. 마지막 성공 시각은 이 프로세스의 시계로
            // 찍었고 원천의 시각 컬럼은 DB 시계로 찍히는데, 둘을 견주려면 드라이버의 시간대
            // 변환을 통과해야 한다 — 그 변환이 어긋나면 조건이 늘 거짓이 되어 증분 보정이
            // <b>아무 방도 고르지 못한 채</b> 「바뀐 방 0건」으로만 보인다. 두 Instant 의 차이는
            // 시간대와 무관하므로, 그 간격만 넘기고 기준 시각은 원천이 자기 시계로 만들게 한다.
            java.time.Duration lookback = store.lastSweepAt()
                    .map(at -> java.time.Duration.between(at.minus(SWEEP_OVERLAP), Instant.now()))
                    .orElse(null);
            var stats = statsReconciliation.run(lookback);
            if (stats.failed() > 0) {
                // 일부 방의 통계가 원천과 맞지 않는다. 그 낡은 값을 새 버전으로 다시 게시하면
                // 「최신」 표를 달고 굳는다 — 이번 회차는 성공 시각을 남기지 않고 물러난다.
                log.error("탐색 보정 중단 — 통계 재계산 실패 {}건. 다음 회차가 다시 시도한다", stats.failed());
                return;
            }
            if (stats.diff() > 0) {
                log.warn("5분 보정에서 통계 {}건을 원천과 맞췄다 — 이벤트 유실 가능성", stats.diff());
            }

            // 변경된 방과 「점수가 내려갈 수 있는 방」만 다시 투영한다 — 후보 전체를 방마다
            // 한 번씩 읽으면 만 단위에서 5분을 넘긴다. 계산 시각을 여기서 갱신해야 인기 응답의
            // calculatedAt 이 실제 기준 시각을 말한다(03:30 값으로 굳으면 늘 하루 전이라고 한다).
            int reprojected = indexer.reprojectChanged(lookback);
            store.markCalculatedAt(startedAt);
            store.markSweptAt(startedAt);
            log.debug("explore_sweep reprojected={} lookback={}", reprojected, lookback);
        } finally {
            store.unlockSweep(token);
        }
    }

    /** 보정 잠금의 수명. 한 회차가 이보다 오래 걸리면 다음 회차가 겹칠 수 있다. */
    private static final java.time.Duration SWEEP_LOCK_TTL = java.time.Duration.ofMinutes(10);

    /**
     * 값까지 대조하는 주기.
     *
     * <p>구조 대조는 매 회차 돌지만 값 대조는 방마다 왕복이라 그럴 수 없다. 이 간격이 곧
     * <b>조용한 값 손상이 살아 있을 수 있는 최대 시간</b>이고, 그 사이에도 5분 보정이 변경된
     * 방의 값을 계속 새로 쓰므로 실제로 굳는 경우는 더 드물다.
     */
    private static final java.time.Duration DEEP_VERIFY_INTERVAL = java.time.Duration.ofMinutes(30);

    /**
     * 재확인 전에 증분 보정이 다시 볼 구간.
     *
     * <p>어긋난 이유가 「아직 안 도착한 갱신」이라면 그 방은 최근에 움직인 방이다. 이 구간을
     * 다시 투영하면 그 갱신이 실제로 도착하고, 그러고도 남는 어긋남만 진짜 유실이다.
     */
    private static final java.time.Duration CONFIRM_WINDOW = java.time.Duration.ofMinutes(15);

    /**
     * 증분 창을 뒤로 넉넉히 민다.
     *
     * <p>「마지막 성공 시각 이후」로 정확히 자르면 그 경계에 걸친 변경이 샌다 — 커밋 시각과
     * 배치가 읽는 시각 사이에는 언제나 틈이 있다. 겹쳐 읽는 비용은 몇 방을 다시 계산하는
     * 것뿐이고, 재계산은 멱등이다.
     */
    private static final java.time.Duration SWEEP_OVERLAP = java.time.Duration.ofMinutes(10);

    /**
     * 매일 03:30 대조 — 점검 창(02:00~03:00)과 00시 판정 배치를 피한다.
     * 유령 제거는 이 배치만 할 수 있다(증분 갱신은 "원천에 없는 행"을 발견하지 못한다).
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    public void reconcile() {
        if (circuit.isOpen()) return;
        // 전수 재구성이 여러 인스턴스에서 겹치면 서로의 중간 상태를 지우며 경합한다.
        // 잠금에는 TTL 을 둔다 — 잡은 인스턴스가 죽으면 다음 회차가 영영 못 돌기 때문이고,
        // 재구성은 멱등이라 만료 뒤 겹쳐 도는 최악의 경우에도 결과가 같다.
        String token = store.tryLockRebuild(REBUILD_LOCK_TTL);
        if (token == null) {
            log.info("탐색 인덱스 대조 건너뜀 — 다른 인스턴스가 수행 중이다");
            return;
        }
        try {
            // <b>먼저 원천에서 통계를 다시 계산한다.</b> 이미 계산된 값을 읽어 옮기기만 하면,
            // 그 값이 틀어졌을 때 대조가 틀린 값을 충실히 복사한다 — 그건 복구가 아니다.
            // 스펙이 이 배치에 요구하는 것은 「원천으로 재계산하고 비교·보정」이다(백엔드 9).
            var stats = statsReconciliation.run();
            if (stats.failed() > 0) {
                // 틀린 값으로 인덱스를 다시 만들면 그 값이 「최신」 표를 달고 굳는다.
                log.error("대조 보류 — 통계 재계산 실패 {}건. 다음 회차가 다시 시도한다", stats.failed());
                return;
            }
            indexer.reindexAll();
            store.countRebuild();
        } catch (RuntimeException e) {
            log.error("탐색 인덱스 대조 실패 — 다음 회차가 다시 시도한다: {}", e.toString());
        } finally {
            store.unlockRebuild(token);
        }
    }

    /** 전수 재구성 잠금의 수명. 한 회차가 이보다 오래 걸리면 다음 회차가 겹칠 수 있다. */
    private static final java.time.Duration REBUILD_LOCK_TTL = java.time.Duration.ofMinutes(30);
}
