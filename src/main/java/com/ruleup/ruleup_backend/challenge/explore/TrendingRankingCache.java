package com.ruleup.ruleup_backend.challenge.explore;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 인기 랭킹 캐시 (탐색 백엔드 테크스펙 §9-3).
 *
 * <p><b>캐시에 넣는 것은 랭킹 정보뿐</b>이다 — challengeId·recentJoins24h·calculatedAt.
 * {@code joinable}·{@code participantCount} 같은 값은 보는 사람과 현재 상태에 따라 달라지므로
 * 공용 캐시에 넣지 않고 요청 시 DB 에서 다시 읽는다. 사용자별 값이 남의 화면에 새는 것을 막는 경계다.
 *
 * <p>캐시를 별도 빈으로 분리한 이유는 self-invocation 이면 {@code @Cacheable} 프록시가 무력화되기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class TrendingRankingCache {

    public static final String CACHE = "challengeTrending";

    /** 서버는 Top 20 을 만들고 홈은 그중 5개를 쓴다 — 상세 진입 유도를 위해 여유를 둔다. */
    private static final int TOP_N = 20;

    private final JdbcTemplate jdbc;

    /** 랭킹 한 줄. 카드 표시값은 담지 않는다. */
    public record Entry(UUID challengeId, int recentJoins24h) {}

    public record Ranking(String calculatedAt, List<Entry> entries) {}

    /**
     * 카테고리별(또는 전체) 인기 Top 20.
     * 후보 조건은 서비스가 아니라 이 쿼리에 고정한다 — 비공개·솔로가 캐시에 들어갈 여지를 없앤다.
     */
    @Cacheable(value = CACHE, key = "#category == null ? 'ALL' : #category")
    public Ranking ranking(String category) {
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
        return new Ranking(calculatedAt.toString(), entries);
    }

    private static UUID toUuid(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
