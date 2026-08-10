package com.ruleup.ruleup_backend.challenge.explore;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 인기 점수 갱신 배치 — 매시 정각 (탐색 백엔드 테크스펙 §9-2, 2026-08-11 10분→1시간 확정).
 *
 * <p>인기 기준은 "현재 인원"이 아니라 <b>최근 24시간에 발생한 가입 수</b>다. 그래서 탈퇴·강퇴했다고
 * 과거 가입 이벤트를 소급해서 빼지 않는다 — 이미 일어난 참여는 그 시간대의 열기를 나타낸다.
 *
 * <p>인기를 Caffeine 에만 두지 않고 {@code challenge_stats} 에 저장하는 이유는, 홈 Top 20 뿐 아니라
 * {@code explore?sort=POPULAR} 에서 <b>필터를 적용한 뒤 인기순 커서 페이징</b>을 해야 하기 때문이다.
 * DB 컬럼이어야 일반 정렬 키로 쓸 수 있다.
 */
@Service
@RequiredArgsConstructor
public class PopularityRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(PopularityRefreshJob.class);

    /** 한 트랜잭션에서 1만 행을 갱신하지 않는다 — 인기는 지연 허용 데이터라 구·신 값이 잠깐 섞여도 된다. */
    private static final int CHUNK = 500;

    private final JdbcTemplate jdbc;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;
    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("popularity_last_success_age", lastSuccessAt, value -> {
                    Instant last = value.get();
                    return last == null ? -1d : Math.max(0, Duration.between(last, Instant.now()).toSeconds());
                })
                .description("마지막 인기 배치 성공 후 경과 초")
                .register(meterRegistry);
    }

    /** 매시 정각 KST. */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    public void runHourly() {
        runOnce();
    }

    /**
     * 탐색 후보 방의 최근 24시간 가입 수·마지막 가입 시각을 다시 센다.
     *
     * @return 갱신에 성공한 방 수. 청크가 하나라도 실패하면 홈 인기 캐시를 비우지 않는다
     *         — 새 값으로 덮어쓰지 않고 직전 값을 그대로 보여 주는 편이 빈 목록보다 낫다
     */
    public int runOnce() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return refreshPopularity();
        } finally {
            sample.stop(Timer.builder("popularity_batch_duration")
                    .description("인기 점수 전체 배치 실행 시간")
                    .register(meterRegistry));
        }
    }

    private int refreshPopularity() {
        // 24시간 가입 이력은 배치 전체에서 한 번만 집계한다. 청크마다 같은 GROUP BY를
        // 반복하면 1만 방 기준으로 가입 이력 전체를 수십 번 다시 읽게 된다.
        List<Popularity> targets = jdbc.query(
                "SELECT c.id, COALESCE(j.c, 0) AS recent_joins, j.last_joined_at " +
                        "FROM challenges c " +
                        "LEFT JOIN (SELECT challenge_id, COUNT(*) AS c, MAX(joined_at) AS last_joined_at " +
                        "           FROM challenge_members " +
                        "           WHERE joined_at >= DATE_SUB(NOW(6), INTERVAL 24 HOUR) " +
                        "           GROUP BY challenge_id) j ON j.challenge_id = c.id " +
                        "WHERE c.mode = 'GROUP' AND c.visibility = 'PUBLIC' " +
                        "  AND c.status IN ('UPCOMING', 'ACTIVE') AND c.deleted_at IS NULL",
                (rs, i) -> new Popularity(rs.getBytes("id"), rs.getInt("recent_joins"),
                        rs.getTimestamp("last_joined_at")));

        int updated = 0;
        boolean allSucceeded = true;
        for (int from = 0; from < targets.size(); from += CHUNK) {
            List<Popularity> chunk = targets.subList(from, Math.min(from + CHUNK, targets.size()));
            try {
                updated += updateChunk(chunk);
            } catch (Exception e) {
                allSucceeded = false;
                log.error("인기 점수 갱신 실패 chunkFrom={} size={}: {}", from, chunk.size(), e.getMessage(), e);
            }
        }

        if (allSucceeded) {
            // 갱신이 끝난 뒤에만 랭킹 캐시를 비운다 → 다음 요청이 새 Top 20 을 읽는다.
            var cache = cacheManager.getCache(TrendingRankingCache.CACHE);
            if (cache != null) cache.clear();
            lastSuccessAt.set(Instant.now());
        }
        log.info("popularity_batch updated={} chunks={} allSucceeded={}",
                updated, (targets.size() + CHUNK - 1) / CHUNK, allSucceeded);
        return updated;
    }

    private int updateChunk(List<Popularity> chunk) {
        int[][] counts = jdbc.batchUpdate(
                "UPDATE challenge_stats SET recent_joins_24h = ?, last_joined_at_24h = ?, " +
                        "popularity_updated_at = NOW(6) WHERE challenge_id = ?",
                chunk,
                CHUNK,
                (ps, value) -> {
                    ps.setInt(1, value.recentJoins());
                    ps.setTimestamp(2, value.lastJoinedAt());
                    ps.setBytes(3, value.challengeId());
                });
        return java.util.Arrays.stream(counts).flatMapToInt(java.util.Arrays::stream)
                .map(count -> count == Statement.SUCCESS_NO_INFO ? 1 : Math.max(count, 0))
                .sum();
    }

    private record Popularity(byte[] challengeId, int recentJoins, Timestamp lastJoinedAt) {}
}
