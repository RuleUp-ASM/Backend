package com.ruleup.ruleup_backend.challenge.explore;

import com.ruleup.ruleup_backend.challenge.explore.store.ExploreCircuitBreaker;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreRedisStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 인기 랭킹 원천 — <b>Redis ZSET 우선, MySQL 폴백</b> (탐색 테크스펙 5-1 · 5-3).
 *
 * <h4>왜 인스턴스 로컬 캐시를 걷어냈나</h4>
 * 예전에는 Caffeine 이었다. 인스턴스마다 캐시가 따로라 <b>서버가 두 대면 같은 사용자가 새로고침할
 * 때마다 다른 순위를 본다.</b> 게다가 무효화가 자기 인스턴스에만 닿아 "방금 참여했는데 인기에
 * 안 뜬다"가 인스턴스 운에 따라 갈렸다. 순위는 공유 저장소가 소유해야 한다.
 *
 * <h4>비어 있음을 정상으로 취급하지 않는다</h4>
 * Redis 가 비었거나 워밍업 전이면 <b>빈 목록을 그대로 내리지 않고</b> 폴백으로 보낸다. 인기 섹션이
 * 조용히 사라지는 것이 가장 나쁜 실패 방식이기 때문이다.
 *
 * <p><b>캐시에 넣는 것은 랭킹 정보뿐</b>이다 — challengeId·recentJoins24h. {@code joinable}·
 * {@code participantCount} 같은 값은 보는 사람과 현재 상태에 따라 달라지므로 공용 저장소에 넣지
 * 않고 요청 시 DB 에서 다시 읽는다. 사용자별 값이 남의 화면에 새는 것을 막는 경계다.
 */
@Component
@RequiredArgsConstructor
public class TrendingRankingSource {

    /** 서버는 Top 20 을 만들고 홈은 그중 5개를 쓴다 — 상세 진입 유도를 위해 여유를 둔다. */
    private static final int TOP_N = 20;

    private final JdbcTemplate jdbc;
    private final ExploreRedisStore store;
    private final ExploreCircuitBreaker circuit;

    /** 랭킹 한 줄. 카드 표시값은 담지 않는다. */
    public record Entry(UUID challengeId, int recentJoins24h) {}

    public record Ranking(String calculatedAt, List<Entry> entries, ExploreDataSource source) {}

    /** 카테고리별(또는 전체) 인기 Top 20. */
    public Ranking ranking(String category) {
        if (circuit.isOpen()) return fromSql(category);

        List<ExploreRedisStore.TrendingEntry> top;
        try {
            // 워밍업 전이면 반쯤 찬 인덱스다 — 있는 것만 보여주면 방이 사라진 것처럼 보인다.
            if (!store.isWarmed()) return fromSql(category);
            top = store.topTrending(category, TOP_N);
        } catch (RuntimeException e) {
            circuit.recordFailure(e);
            return fromSql(category);
        }

        // 비어 있음을 정상으로 취급하지 않는다. 다만 이건 <b>장애가 아니라 인덱스가 덜 찬 상태</b>라
        // 회로에 실패로 세지 않는다 — 방이 정말 하나도 없는 서비스 초기에 회로가 계속 열려 버린다.
        if (top.isEmpty()) return fromSql(category);

        return new Ranking(Instant.now().toString(),
                top.stream().map(t -> new Entry(t.challengeId(), t.recentJoins24h())).toList(),
                ExploreDataSource.REDIS);
    }

    /**
     * 폴백 — {@code challenge_stats} 스냅샷을 읽는다. 5분 스윕이 이 값을 함께 갱신하므로
     * 폴백 중에도 최대 5분 지연으로 따라간다(이관이 아니라 이중화).
     *
     * <p>후보 조건은 서비스가 아니라 이 쿼리에 고정한다 — 비공개·솔로가 새어 나갈 여지를 없앤다.
     */
    private Ranking fromSql(String category) {
        StringBuilder sql = new StringBuilder(
                "SELECT c.id, s.recent_joins_24h, s.popularity_updated_at " +
                        "FROM challenges c JOIN challenge_stats s ON s.challenge_id = c.id " +
                        "WHERE c.mode = 'GROUP' AND c.visibility = 'PUBLIC' " +
                        "  AND c.status IN ('UPCOMING', 'ACTIVE') AND c.deleted_at IS NULL ");
        Object[] args = (category != null)
                ? new Object[]{category, TOP_N} : new Object[]{TOP_N};
        if (category != null) sql.append("AND c.category = ? ");
        sql.append("ORDER BY s.recent_joins_24h DESC, s.last_joined_at_24h DESC, c.id DESC LIMIT ?");

        List<Object[]> rows = jdbc.query(sql.toString(),
                (rs, i) -> new Object[]{toUuid(rs.getBytes(1)), rs.getInt(2), rs.getTimestamp(3)}, args);

        List<Entry> entries = rows.stream()
                .map(r -> new Entry((UUID) r[0], (Integer) r[1]))
                .toList();
        // 계산 기준 시각은 랭킹에 든 방들의 최신 갱신 시각. 아직 배치 전이면 지금 시각으로 둔다.
        Instant calculatedAt = rows.stream()
                .map(r -> (java.sql.Timestamp) r[2])
                .filter(java.util.Objects::nonNull)
                .map(java.sql.Timestamp::toInstant)
                .max(Instant::compareTo)
                .orElseGet(Instant::now);
        return new Ranking(calculatedAt.toString(), entries, ExploreDataSource.MYSQL);
    }

    private static UUID toUuid(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
