package com.ruleup.ruleup_backend.challenge.explore.store;

import com.ruleup.ruleup_backend.challenge.explore.ExploreSort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 원천(MySQL) → 파생(Redis) 투영.
 *
 * <h4>pull/TTL 이 아니라 push 인 이유</h4>
 * 키를 만료시켜 다음 조회에서 다시 만드는 방식은 <b>목록에 노출되는 키가 만료되면 그 방이
 * 사라진다</b>. 탐색은 "없는 것"과 "아직 안 만든 것"을 구분할 수 없으므로, 만료로 비우는 대신
 * 값이 바뀌는 순간마다 밀어 넣는다(탐색 테크스펙 5-1).
 *
 * <h4>정렬 멤버는 교체다</h4>
 * 정렬 키가 멤버 문자열에 박혀 있으므로({@link SortKeyCodec}) 값이 바뀌면 <b>예전 멤버를 지우고
 * 새 멤버를 넣어야</b> 한다. 지우지 않으면 같은 방이 두 위치에 남아 목록에 중복으로 뜬다.
 * 그래서 현재 멤버 문자열을 표시값 HASH 에 같이 보관해 다음 갱신 때 지울 대상을 안다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExploreIndexer {

    /** HASH 안에서 "이 방의 현재 정렬 멤버" 를 담는 필드 접두사. */
    private static final String MEMBER_FIELD = "m:";

    /**
     * 인기 후보 조회와 같은 노출 조건. <b>여기 한 곳에만 둔다</b> — 조건이 갈라지면
     * 비공개·솔로 방이 어느 한쪽 경로로 새어 나온다.
     */
    private static final String VISIBLE_CONDITION =
            "c.mode = 'GROUP' AND c.visibility = 'PUBLIC' " +
            "AND c.status IN ('UPCOMING', 'ACTIVE') AND c.deleted_at IS NULL";

    private static final String SELECT_ROW =
            "SELECT c.id, c.category, c.verification_type, c.status, c.visibility, c.mode, " +
            "       c.deleted_at, c.participant_count, c.created_at, c.end_date, " +
            "       s.completion_rate, s.retention_rate, " +
            // 인기는 상승이 즉시여야 하므로 배치가 채운 값이 아니라 지금 센 값을 쓴다.
            "       (SELECT COUNT(*) FROM challenge_members m " +
            "         WHERE m.challenge_id = c.id " +
            "           AND m.joined_at >= DATE_SUB(NOW(6), INTERVAL 24 HOUR)) AS recent_joins, " +
            "       (SELECT MAX(m.joined_at) FROM challenge_members m " +
            "         WHERE m.challenge_id = c.id " +
            "           AND m.joined_at >= DATE_SUB(NOW(6), INTERVAL 24 HOUR)) AS last_joined " +
            "FROM challenges c LEFT JOIN challenge_stats s ON s.challenge_id = c.id ";

    private final JdbcTemplate jdbc;
    private final ExploreRedisStore store;
    private final ExploreCircuitBreaker circuit;

    /** 한 방을 다시 투영한다. Redis 가 죽어 있으면 조용히 건너뛴다 — 원천은 이미 옳다. */
    public void index(UUID challengeId) {
        circuit.callQuietly(() -> {
            Row row = loadOne(challengeId);
            if (row == null) return;
            apply(row);
        });
    }

    /** 방이 사라졌을 때(하드 삭제) 파생에서도 지운다. */
    public void remove(UUID challengeId, String category) {
        circuit.callQuietly(() -> {
            evictFromSorted(challengeId);
            store.removeTrending(challengeId, category);
            removeFromFilters(challengeId, category, null);
            store.deleteStats(challengeId);
        });
    }

    /**
     * 전체 재구성 — 워밍업과 03:30 대조 배치가 쓴다.
     *
     * <p>기존 키를 <b>먼저 비운다.</b> 원천에서 사라진 방이 파생에 남아 있으면 목록에 유령이 뜨는데,
     * 증분 갱신만으로는 그런 행을 발견할 방법이 없다 — 대조 배치의 존재 이유가 이것이다.
     */
    public int reindexAll() {
        List<Row> rows = jdbc.query(SELECT_ROW + "WHERE " + VISIBLE_CONDITION, (rs, i) -> mapRow(rs));
        store.flushDerived();
        for (Row row : rows) apply(row);
        store.markWarmed();
        log.info("explore_reindex rows={}", rows.size());
        return rows.size();
    }

    /** 워밍업이 끝났는가. 끝나기 전에는 조회를 Redis 로 보내면 안 된다 — 반쯤 찬 목록이 나간다. */
    public boolean isWarmed() {
        return store.isWarmed();
    }

    // ===== 투영 =====

    private void apply(Row row) {
        if (!row.visible()) {
            evictFromSorted(row.id());
            store.removeTrending(row.id(), row.category());
            removeFromFilters(row.id(), row.category(), row.verificationType());
            store.deleteStats(row.id());
            return;
        }

        Map<String, String> previous = store.stats(row.id());
        Map<String, String> next = new HashMap<>();

        for (ExploreSort sort : ExploreSort.values()) {
            String oldMember = previous.get(MEMBER_FIELD + sort.name());
            String newMember = memberFor(sort, row);
            // 표본 미달 방은 memberFor 가 null 을 주므로 그 정렬의 ZSET 에서 빠진다 — 값 없는 방을
            // 최하위로 붙이면 "완주율 순"이라는 약속이 깨지기 때문이다(정책 §4.4).
            store.replaceSorted(sort, oldMember, newMember);
            if (newMember != null) next.put(MEMBER_FIELD + sort.name(), newMember);
        }

        next.put("participantCount", String.valueOf(row.participantCount()));
        next.put("recentJoins24h", String.valueOf(row.recentJoins()));
        if (row.completionRate() != null) next.put("completionRate", row.completionRate().toString());
        if (row.retentionRate() != null) next.put("retentionRate", row.retentionRate().toString());
        store.putStats(row.id(), next);

        store.addToSet(ExploreKeys.VISIBLE, row.id());
        if (row.category() != null) store.addToSet(ExploreKeys.category(row.category()), row.id());
        // 인증 방식이 비어 있는 방은 해당 필터의 후보가 아니다 — "null" 이라는 이름의 집합을 만들지 않는다.
        if (row.verificationType() != null) {
            store.addToSet(ExploreKeys.verifyType(row.verificationType()), row.id());
        }
        store.setTrending(row.id(), row.category(), row.recentJoins());
    }

    /**
     * 정렬 멤버 계산. 표본 미달이면 null 을 줘서 그 정렬의 ZSET 에 넣지 않는다.
     * 값 정규화는 SQL 경로가 쓰는 컬럼과 <b>같은 값</b>이어야 두 경로의 순서가 같아진다.
     */
    private String memberFor(ExploreSort sort, Row row) {
        return switch (sort) {
            case POPULAR -> SortKeyCodec.member(sort,
                    SortKeyCodec.ofCount(row.recentJoins()),
                    SortKeyCodec.ofInstantMillis(row.lastJoinedMillis()), row.id());
            case PARTICIPANTS -> SortKeyCodec.member(sort,
                    SortKeyCodec.ofCount(row.participantCount()), 0, row.id());
            case COMPLETION_RATE -> row.completionRate() == null ? null
                    : SortKeyCodec.member(sort, SortKeyCodec.ofRate(row.completionRate()), 0, row.id());
            case SUCCESS_FAIL_RATIO -> row.retentionRate() == null ? null
                    : SortKeyCodec.member(sort, SortKeyCodec.ofRate(row.retentionRate()), 0, row.id());
            case RECENT -> SortKeyCodec.member(sort,
                    SortKeyCodec.ofInstantMillis(row.createdAtMillis()), 0, row.id());
            case DEADLINE -> SortKeyCodec.member(sort,
                    SortKeyCodec.ofEpochDay(row.endEpochDay()), 0, row.id());
        };
    }

    private void evictFromSorted(UUID challengeId) {
        Map<String, String> previous = store.stats(challengeId);
        for (ExploreSort sort : ExploreSort.values()) {
            store.removeSorted(sort, previous.get(MEMBER_FIELD + sort.name()));
        }
    }

    /**
     * 필터 집합에서 뺀다. 카테고리·인증 방식을 모르면(하드 삭제로 행이 이미 사라진 경우) 그 키는
     * 건너뛴다 — 남은 잔여 멤버는 노출 후보 집합에서 이미 빠졌으므로 목록에 뜨지 않고,
     * 03:30 대조가 걷어낸다.
     */
    private void removeFromFilters(UUID challengeId, String category, String verifyType) {
        store.removeFromSet(ExploreKeys.VISIBLE, challengeId);
        if (category != null) store.removeFromSet(ExploreKeys.category(category), challengeId);
        if (verifyType != null) store.removeFromSet(ExploreKeys.verifyType(verifyType), challengeId);
    }

    // ===== 조회 =====

    private Row loadOne(UUID challengeId) {
        List<Row> rows = jdbc.query(SELECT_ROW + "WHERE c.id = ?",
                (rs, i) -> mapRow(rs), (Object) toBytes(challengeId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private record Row(UUID id, String category, String verificationType, boolean visible,
                       int participantCount, Long createdAtMillis, Long endEpochDay,
                       Double completionRate, Double retentionRate,
                       int recentJoins, Long lastJoinedMillis) {}

    private Row mapRow(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        boolean visible = "GROUP".equals(rs.getString("mode"))
                && "PUBLIC".equals(rs.getString("visibility"))
                && ("UPCOMING".equals(status) || "ACTIVE".equals(status))
                && rs.getTimestamp("deleted_at") == null;

        Timestamp created = rs.getTimestamp("created_at");
        Timestamp lastJoined = rs.getTimestamp("last_joined");
        java.sql.Date endDate = rs.getDate("end_date");

        return new Row(
                toUuid(rs.getBytes("id")),
                rs.getString("category"),
                rs.getString("verification_type"),
                visible,
                rs.getInt("participant_count"),
                created == null ? null : created.getTime(),
                endDate == null ? null : endDate.toLocalDate().toEpochDay(),
                (Double) rs.getObject("completion_rate", Double.class),
                (Double) rs.getObject("retention_rate", Double.class),
                rs.getInt("recent_joins"),
                lastJoined == null ? null : lastJoined.getTime());
    }

    private static byte[] toBytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    private static UUID toUuid(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }

    /** 전체 후보의 id 목록 — 5분 스윕이 인기 하락분을 다시 밀어 넣을 때 쓴다. */
    public List<UUID> visibleCandidateIds() {
        List<UUID> ids = new ArrayList<>();
        jdbc.query("SELECT c.id FROM challenges c WHERE " + VISIBLE_CONDITION,
                rs -> { ids.add(toUuid(rs.getBytes(1))); });
        return ids;
    }
}
