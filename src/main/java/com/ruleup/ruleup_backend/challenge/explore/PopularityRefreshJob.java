package com.ruleup.ruleup_backend.challenge.explore;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

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
        List<byte[]> targets = jdbc.query(
                "SELECT id FROM challenges WHERE status IN ('UPCOMING', 'ACTIVE')",
                (rs, i) -> rs.getBytes(1));

        int updated = 0;
        boolean allSucceeded = true;
        for (int from = 0; from < targets.size(); from += CHUNK) {
            List<byte[]> chunk = targets.subList(from, Math.min(from + CHUNK, targets.size()));
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
        }
        log.info("popularity_batch updated={} chunks={} allSucceeded={}",
                updated, (targets.size() + CHUNK - 1) / CHUNK, allSucceeded);
        return updated;
    }

    private int updateChunk(List<byte[]> chunk) {
        String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
        // 가입 이력이 없는 방은 0 으로 되돌린다(24시간이 지나 창을 벗어난 경우).
        return jdbc.update(
                "UPDATE challenge_stats s " +
                        "LEFT JOIN (SELECT challenge_id, COUNT(*) AS c, MAX(joined_at) AS m " +
                        "           FROM challenge_members " +
                        "           WHERE joined_at >= DATE_SUB(NOW(6), INTERVAL 24 HOUR) " +
                        "           GROUP BY challenge_id) j ON j.challenge_id = s.challenge_id " +
                        "SET s.recent_joins_24h = COALESCE(j.c, 0), " +
                        "    s.last_joined_at_24h = j.m, " +
                        "    s.popularity_updated_at = NOW(6) " +
                        "WHERE s.challenge_id IN (" + placeholders + ")",
                chunk.toArray());
    }
}
