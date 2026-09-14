package com.ruleup.ruleup_backend.challenge.explore.store;

import com.ruleup.ruleup_backend.challenge.explore.ExploreSort;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
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
            // <b>계산 리비전</b> — 이 값들을 읽은 기준 시각. 같은 문장에서 받으므로 왕복이 늘지
            // 않고, 아래 24시간 창도 같은 NOW(6) 으로 잘리므로 <b>결과의 신선도를 그대로</b>
            // 나타낸다. 원천 리비전이 같은 두 계산의 순서는 이 값이 가른다.
            "       NOW(6) AS read_at, " +
            // 인기는 상승이 즉시여야 하므로 배치가 채운 값이 아니라 지금 센 값을 쓴다.
            // 세는 대상은 <b>가입 사건</b>이다 — 멤버십은 사람당 한 줄인 상태라, 그 줄의 시각으로
            // 세면 재입장이 잡히지 않고(처음 들어온 날이 굳어 있다) 덮어쓰면 첫 가입일이 사라진다.
            "       (SELECT COUNT(*) FROM challenge_join_events e " +
            "         WHERE e.challenge_id = c.id " +
            "           AND e.joined_at >= DATE_SUB(NOW(6), INTERVAL 24 HOUR)) AS recent_joins, " +
            "       (SELECT MAX(e.joined_at) FROM challenge_join_events e " +
            "         WHERE e.challenge_id = c.id " +
            "           AND e.joined_at >= DATE_SUB(NOW(6), INTERVAL 24 HOUR)) AS last_joined, " +
            // <b>원천 리비전</b> — 이 방에 관한 원천이 마지막으로 움직인 시각. 투영 버전이 곧
            // 이 값이라, 「더 새 원천이 이긴다」가 문자 그대로 성립한다. 읽기 전에 번호를 미리
            // 받아 두면 <b>먼저 받은 쪽이 나중에 읽을</b> 수 있어, 옛 값이 큰 번호를 달고 새 값을
            // 덮는다 — 순서를 정하는 기준은 읽은 시각이 아니라 읽힌 데이터여야 한다.
            "       GREATEST(c.updated_at, " +
            "                COALESCE((SELECT MAX(m.updated_at) FROM challenge_members m " +
            "                           WHERE m.challenge_id = c.id), c.updated_at), " +
            "                COALESCE((SELECT MAX(e2.joined_at) FROM challenge_join_events e2 " +
            "                           WHERE e2.challenge_id = c.id), c.updated_at), " +
            // 통계 보정은 완주율·유지율을 <b>바꾸면서</b> 챌린지·멤버십은 건드리지 않는다.
            // 여기 넣지 않으면 그 변경이 리비전에 흔적을 남기지 않아, 보정 전 값을 읽은 회차와
            // 보정 후 값을 읽은 회차가 <b>같은 버전</b>으로 겨루게 된다.
            "                COALESCE(s.updated_at, c.updated_at)) AS source_revision " +
            "FROM challenges c LEFT JOIN challenge_stats s ON s.challenge_id = c.id ";

    /**
     * 구조 대조와 유령 제거가 쓰는 <b>가벼운</b> 후보 조회.
     *
     * <p>「어느 방이 어느 집합·정렬에 들어가야 하는가」는 카테고리·인증 방식과 두 비율의 유무만
     * 알면 정해진다. {@link #SELECT_ROW} 는 그 답을 내려고 가입 집계와 리비전 상관 서브쿼리를
     * 함께 도는데, 5분마다 후보 전체에 그걸 돌릴 이유가 없다 — 값 대조를 할 때만 필요한 비용이다.
     */
    private static final String SELECT_CANDIDATE =
            "SELECT c.id, c.category, c.verification_type, s.completion_rate, s.retention_rate " +
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

    /**
     * 방이 사라졌을 때(하드 삭제) 파생에서도 지운다.
     *
     * <p>비노출 전환과 <b>같은 경로</b>를 쓴다. 키를 하나씩 지우면 중간 실패에 일부만 남고,
     * HASH 를 통째로 지우면 버전이 사라져 그때 돌던 오래된 투영이 삭제된 방을 다시 넣는다.
     */
    public void remove(UUID challengeId, String category) {
        circuit.callQuietly(() -> {
            long now = removalVersion();
            store.removeProjection(challengeId, now, now,
                    clearingSpecs(), allFilterSets(), allTrendingKeys());
        });
    }

    /** 인증 방식 필터의 전체 값. 집합에서 빼려면 어떤 이름들이 있는지 알아야 한다. */
    private static final java.util.List<String> VERIFY_TYPES = java.util.List.of("AUTO", "MANUAL");

    /**
     * 전체 재구성 — 워밍업과 03:30 대조 배치가 쓴다.
     *
     * <p>기존 키를 <b>먼저 비운다.</b> 원천에서 사라진 방이 파생에 남아 있으면 목록에 유령이 뜨는데,
     * 증분 갱신만으로는 그런 행을 발견할 방법이 없다 — 대조 배치의 존재 이유가 이것이다.
     */
    public int reindexAll() {
        List<Row> rows = jdbc.query(SELECT_ROW + "WHERE " + VISIBLE_CONDITION, (rs, i) -> mapRow(rs));

        // <b>먼저 채우고 나중에 걷어낸다.</b> 예전에는 비우고 채웠는데, 파생 인덱스로만 응답하게
        // 된 뒤로는 그 사이가 곧 503 구간이다 — 매일 밤 03:30 마다 목록이 잠시 사라진다.
        // 채우는 동안 인덱스는 「현재 + 사라질 것들」의 합집합이라 한 번도 비지 않고, 마지막에
        // 원천에 없는 멤버만 빼면 유령 제거라는 이 배치의 존재 이유도 그대로 지켜진다.
        for (Row row : rows) apply(row);
        pruneGhosts(aliveHex(rows));
        pruneOrphanSortMembers(rows);

        store.markCalculatedAt(java.time.Instant.now());
        // 여기까지 왔으면 인덱스는 온전하다 — 준비 완료를 올린다. 통계가 원천과 맞는지는
        // <b>이 메서드를 부르기 전에</b> 확인해야 한다(호출부 참조). 만들어 놓고 나중에 내리면
        // 그 사이 짧게 「준비됐지만 틀린」 상태가 응답으로 나간다.
        store.markWarmed();
        log.info("explore_reindex rows={}", rows.size());
        return rows.size();
    }

    /**
     * 원천에 없는데 파생에만 남은 멤버를 걷어낸다.
     *
     * <p>증분 갱신은 이런 행을 발견할 방법이 없다 — 「사라졌다」는 이벤트가 유실되면 그 방은
     * 영영 목록에 뜬다. 노출 후보 집합과 인기 ZSET 만 훑으면 된다. 카테고리·인증 방식 집합은
     * 노출 후보와 교차해 쓰이므로, 후보에서 빠지면 결과에도 나오지 않는다.
     */
    /**
     * 지금 후보인 방들을 기준으로 파생에 남은 유령을 걷어낸다 — 5분 스윕이 부른다.
     *
     * <p>전수 재구성과 달리 값은 건드리지 않고 <b>사라진 것만</b> 뺀다. 종료·비공개 전환
     * 이벤트가 유실되면 그 방은 증분 갱신으로는 영영 발견되지 않는다.
     */
    public void pruneStaleCandidates() {
        // <b>id 만 있으면 된다.</b> 유령 판별은 「원천에 있는가」뿐이라, 투영 한 행을 만드는 데
        // 드는 가입 집계·리비전 상관 서브쿼리를 여기서 치를 이유가 없다.
        pruneGhosts(visibleCandidateIds().stream()
                .map(ExploreKeys::hex).collect(java.util.stream.Collectors.toSet()));
    }

    /**
     * 정본과 다른 정렬 멤버를 걷어낸다 — 전수 재구성만 할 수 있는 복구다.
     *
     * <p>Lua 는 <b>HASH 가 기억하는 멤버</b>만 지우고 새 멤버를 넣는다. 그래서 HASH 가 먼저
     * 유실된 뒤(승격 직후 부분 복제가 그렇다) 새 투영이 들어오면, 예전 멤버는 지워질 기회를
     * 잃고 ZSET 에 남는다. 그 멤버에도 같은 방 id 가 박혀 있어 <b>유령 제거는 「살아 있는 방」으로
     * 보고 지나간다</b> — 목록에 같은 방이 두 번 뜨는데 아무도 그것을 치우지 않는 상태가 된다.
     *
     * <p><b>원천 스냅샷을 정본으로 삼지 않는다.</b> 스냅샷에 든 멤버라는 이유로 건너뛰면 이런
     * 순서에서 유물이 살아남는다 — 스냅샷을 읽은 뒤 더 새 원천 변경이 다른 멤버로 투영되면,
     * 그 투영은 HASH 가 기억하던 멤버만 지우므로 유물은 그대로 남고, 스냅샷의 투영은 버전
     * 검사에 막혀 적용되지 않으며, 정리 단계는 「스냅샷의 정본」이라며 유물을 건너뛴다.
     * 셋이 겹치면 같은 방이 두 멤버로 선 채 준비 완료가 올라간다.
     *
     * <p>그래서 정본은 <b>Redis 에게 묻는다</b>. 방마다 묻지 않고 파이프라인으로 한 번에 받아
     * 왕복을 하나로 줄이고, 그 답조차 낡았을 수 있으므로 실제 삭제는
     * {@link ExploreRedisStore#removeSortedMemberIfStale} 이 <b>지우는 순간</b> 다시 확인한다.
     */
    private void pruneOrphanSortMembers(List<Row> rows) {
        ExploreSort[] sorts = ExploreSort.values();
        List<String> fields = java.util.Arrays.stream(sorts)
                .map(sort -> MEMBER_FIELD + sort.name()).toList();
        Map<UUID, List<String>> canonical =
                store.sortMembersOf(rows.stream().map(Row::id).toList(), fields);

        int removed = 0;
        for (int i = 0; i < sorts.length; i++) {
            java.util.Set<String> live = new java.util.HashSet<>();
            for (List<String> perRoom : canonical.values()) {
                String member = perRoom.get(i);
                if (member != null) live.add(member);
            }

            String key = ExploreKeys.sorted(sorts[i]);
            String field = fields.get(i);
            for (String member : store.zsetMembersOf(key)) {
                if (live.contains(member)) continue;
                UUID id = SortKeyCodec.idOf(member, sorts[i]);
                if (store.removeSortedMemberIfStale(ExploreKeys.stats(id), key, field, member)) removed++;
            }
        }
        if (removed > 0) log.warn("정본과 다른 정렬 멤버 {}건을 걷어냈다 — 같은 방이 두 번 서 있었다", removed);
    }

    private static java.util.Set<String> aliveHex(List<Row> rows) {
        return rows.stream().map(r -> ExploreKeys.hex(r.id()))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * @param alive 후보 스냅샷의 id hex. <b>스냅샷이라 이미 낡았을 수 있다</b> — 그 전제가
     *              아래 재확인의 이유이고, 시험이 낡은 스냅샷을 그대로 넣어 볼 수 있게 인자로 받는다
     */
    public void pruneGhosts(java.util.Set<String> alive) {
        // 파생에만 남은 방들을 모은다 — 노출 후보·인증 SET·정렬 ZSET 어디에 있든 하나로 본다.
        java.util.Set<String> ghosts = new java.util.HashSet<>(store.membersOf(ExploreKeys.VISIBLE));
        for (String type : VERIFY_TYPES) ghosts.addAll(store.membersOf(ExploreKeys.verifyType(type)));
        for (InterestCategory c : InterestCategory.values()) {
            ghosts.addAll(store.membersOf(ExploreKeys.category(c.name())));
        }
        for (ExploreSort sort : ExploreSort.values()) {
            for (String m : store.zsetMembersOf(ExploreKeys.sorted(sort))) {
                ghosts.add(ExploreKeys.hex(SortKeyCodec.idOf(m, sort)));
            }
        }
        ghosts.removeAll(alive);
        if (ghosts.isEmpty()) return;

        // <b>스냅샷만 보고 지우지 않는다.</b> 후보 목록을 읽은 뒤 공개로 바뀐 방은 이 목록에
        // 없으므로 유령으로 보이는데, 그 사이 이벤트가 이미 옳게 투영해 뒀을 수 있다. 거기에
        // 「지금 시각」으로 tombstone 을 찍으면 그 방의 원천 리비전보다 큰 버전이 새겨져,
        // 이후 재투영이 전부 거부된다 — <b>다음 원천 변경까지 탐색에서 영영 사라진다.</b>
        // 그래서 지우기 직전에 그 방 하나를 원천에서 다시 읽고, 읽힌 대로 처리한다.
        int removed = 0;
        int republished = 0;
        for (String hex : ghosts) {
            UUID id = ExploreKeys.fromHex(hex);
            Row now = loadOne(id);
            if (now == null) {
                // 원천에 행 자체가 없다(하드 삭제). 돌아올 수 없으니 DB 시계로 못을 박는다.
                long at = removalVersion();
                store.removeProjection(id, at, at, clearingSpecs(), allFilterSets(), allTrendingKeys());
                removed++;
            } else if (now.visible()) {
                // 스냅샷 이후 공개된 방이다 — 유령이 아니라 <b>새로 들어온 후보</b>다.
                apply(now);
                republished++;
            } else {
                // 정말 후보에서 빠졌다. 버전은 그 <b>비노출 전환의 리비전</b>이라, 나중에 다시
                // 공개되면 더 큰 리비전이 이 tombstone 을 정상적으로 넘어선다.
                apply(now);
                removed++;
            }
        }
        log.info("explore_prune ghosts={} removed={} republished={}",
                ghosts.size(), removed, republished);
    }

    /**
     * 제거에 쓰는 버전 — <b>DB 의 지금</b>이다.
     *
     * <p>지울 방은 원천에 없으니 리비전을 읽을 수 없다. DB 시계를 쓰면 모든 인스턴스가 같은
     * 기준을 보고, 아직 살아 있는 방의 리비전보다 확실히 크다 — 즉 「지운다」가 그 시점까지의
     * 어떤 투영보다 새 결정이 된다. 애플리케이션 시계를 쓰면 인스턴스마다 어긋난다.
     */
    private long removalVersion() {
        java.sql.Timestamp now = jdbc.queryForObject("SELECT NOW(6)", java.sql.Timestamp.class);
        return (now != null) ? micros(now) : System.currentTimeMillis() * 1_000L;
    }

    /** 워밍업이 끝났는가. 끝나기 전에는 조회를 Redis 로 보내면 안 된다 — 반쯤 찬 목록이 나간다. */
    public boolean isWarmed() {
        return store.isWarmed();
    }

    /**
     * 파생이 원천과 <b>구조마다</b> 맞는가.
     *
     * <p>{@code ex:ready} 는 「만든 적 있다」는 표식일 뿐 지금 온전하다는 뜻이 아니다(백엔드 8-2).
     * 승격 직후 복제가 덜 따라오면 플래그와 계산 시각은 남고 일부 구조만 빈 채로 돌아온다 —
     * 그러면 목록이 <b>정상적인 빈 결과</b>로 200 을 낸다. 가장 조용한 실패 방식이다.
     *
     * <p><b>수를 세는 것으로는 부족하다.</b> 「정상 방 하나가 빠지고 유령 하나가 남은」 상태는
     * 수가 같고, 인기 ZSET 하나만 통째로 비어도 후보·정렬은 멀쩡해 보인다 — 그때 인기 API 는
     * 빈 200 을 낸다. 그래서 <b>모든 구조의 소속을 원천이 기대하는 집합과 직접 맞춘다.</b>
     *
     * <ul>
     *   <li>노출 후보 SET · 카테고리 SET 12종 · 인증 방식 SET</li>
     *   <li>정렬 ZSET 6종 — 수가 아니라 담긴 id 구성(비율 정렬은 표본 미달 제외분을 감안)</li>
     *   <li>인기 ZSET — {@code trending:all} 과 카테고리별</li>
     *   <li>방마다 표시값 HASH 가 있고, 그 버전이 원천 리비전에서 지나치게 뒤처지지 않는가</li>
     * </ul>
     *
     * <p>세대 manifest 를 따로 두지 않는다 — manifest 자체가 또 하나의 파생값이 되어 같은 문제를
     * 되풀이한다. 원천에서 기대집합을 만들어 대조하는 편이 틀릴 여지가 없다.
     */
    public boolean projectionMatchesSource() {
        return projectionMatchesSource(true);
    }

    /**
     * @param deep 값까지 볼 것인가. 구조 대조의 Redis 왕복 수는 키 수로 고정되지만 전송량은
     *             방 수에 비례한다. 값 대조는 방마다 왕복하므로 호출부가 주기를 정한다.
     */
    public boolean projectionMatchesSource(boolean deep) {
        // 원천을 읽기 전의 기준을 고정한다. 대조가 2분 넘게 걸려도 도중의 변경이 유예 창에서
        // 빠지면 안 된다. DB 시각을 문자열로 왕복시켜 JVM/드라이버의 시간대 변환을 피한다.
        String changedSince = jdbc.queryForObject(
                "SELECT CAST((NOW(6) - INTERVAL ? SECOND) AS CHAR)", String.class, SETTLE.toSeconds());
        List<Candidate> rows = deep
                ? jdbc.query(SELECT_ROW + "WHERE " + VISIBLE_CONDITION, (rs, i) -> mapRow(rs))
                        .stream().map(Candidate::of).toList()
                : jdbc.query(SELECT_CANDIDATE + "WHERE " + VISIBLE_CONDITION, (rs, i) -> new Candidate(
                        toUuid(rs.getBytes("id")), rs.getString("category"),
                        rs.getString("verification_type"),
                        (Double) rs.getObject("completion_rate", Double.class),
                        (Double) rs.getObject("retention_rate", Double.class), null));

        // 원천이 기대하는 소속을 키마다 만든다. 비어 있어야 하는 키도 <b>빈 집합으로</b> 넣는다 —
        // 넣지 않으면 그 키에 남은 유령을 대조가 보지 못한다.
        Map<String, java.util.Set<String>> expectedSets = new java.util.LinkedHashMap<>();
        for (String key : allFilterSets()) expectedSets.put(key, new java.util.HashSet<>());
        Map<String, java.util.Set<String>> expectedZsets = new java.util.LinkedHashMap<>();
        for (String key : allTrendingKeys()) expectedZsets.put(key, new java.util.HashSet<>());
        Map<ExploreSort, java.util.Set<String>> expectedSorted = new java.util.EnumMap<>(ExploreSort.class);
        for (ExploreSort sort : ExploreSort.values()) expectedSorted.put(sort, new java.util.HashSet<>());

        for (Candidate row : rows) {
            String hex = ExploreKeys.hex(row.id());
            expectedSets.get(ExploreKeys.VISIBLE).add(hex);
            expectedZsets.get(ExploreKeys.TRENDING_ALL).add(hex);
            if (row.category() != null) {
                expectedSets.get(ExploreKeys.category(row.category())).add(hex);
                expectedZsets.get(ExploreKeys.trendingCategory(row.category())).add(hex);
            }
            if (row.verificationType() != null) {
                expectedSets.get(ExploreKeys.verifyType(row.verificationType())).add(hex);
            }
            for (ExploreSort sort : ExploreSort.values()) {
                // 표본 미달 방은 그 정렬 ZSET 의 멤버가 아니다 — 기대집합에서도 빠져야 한다.
                if (row.includedIn(sort)) expectedSorted.get(sort).add(hex);
            }
        }

        // 먼저 불일치한 방을 모은다. 유예 대상을 Redis 조회 전에 읽으면 그 직후 커밋된 방은
        // 기대집합과 유예집합에 없고 Redis 에만 있어서 정상 투영도 손상으로 보인다.
        java.util.Set<String> mismatched = new java.util.HashSet<>();

        for (Map.Entry<String, java.util.Set<String>> e : expectedSets.entrySet()) {
            collectMismatches(store.membersOf(e.getKey()), e.getValue(), mismatched);
        }
        for (Map.Entry<String, java.util.Set<String>> e : expectedZsets.entrySet()) {
            collectMismatches(store.zsetMembersOf(e.getKey()), e.getValue(), mismatched);
        }
        for (ExploreSort sort : ExploreSort.values()) {
            // 멤버에 정렬값이 박혀 있으므로 id 로 되짚어 구성을 본다 — 수만 세면 「하나 빠지고
            // 유령 하나 남은」 상태를 통과시킨다.
            java.util.Set<String> members = store.zsetMembersOf(ExploreKeys.sorted(sort));
            java.util.Set<String> actual = members.stream()
                    .map(m -> ExploreKeys.hex(SortKeyCodec.idOf(m, sort)))
                    .collect(java.util.stream.Collectors.toSet());
            // 같은 방이 <b>값만 다른 멤버</b>로 두 번 설 수 있다 — 멤버 문자열이 서로 다르면
            // ZSET 은 둘 다 받는다. id 집합만 보면 그 중복이 그대로 지나간다(목록에 같은 방이
            // 두 번 뜬다). 멤버 수와 id 수가 어긋나는 것이 곧 그 상태다.
            if (members.size() != actual.size()) {
                log.warn("정렬 ZSET 에 같은 방이 두 번 서 있다 sort={} members={} ids={}",
                        sort, members.size(), actual.size());
                return false;
            }
            collectMismatches(actual, expectedSorted.get(sort), mismatched);
        }

        if (deep) {
            for (Candidate row : rows) {
                String hex = ExploreKeys.hex(row.id());
                Map<Object, Object> hash = store.getStats(row.id());
                Object version = hash.get(ExploreRedisStore.VERSION_FIELD);
                if (hash.isEmpty() || version == null) {
                    mismatched.add(hex);
                    continue;
                }
                // 버전이 원천 리비전보다 한참 뒤처졌는지 본다. 방금 커밋된 변경의 투영은
                // 아직 도착하지 않았을 수 있으므로 이 차이만으로 바로 손상으로 확정하지 않는다.
                long projected;
                try {
                    projected = Long.parseLong(String.valueOf(version));
                } catch (NumberFormatException e) {
                    mismatched.add(hex);
                    continue;
                }
                if (row.full().version() - projected > STALE_PROJECTION_TOLERANCE_MICROS) {
                    mismatched.add(hex);
                    continue;
                }
                if (!valuesMatch(row.full(), hash, projected)) mismatched.add(hex);
            }
        }

        // 모든 Redis 읽기가 끝난 뒤에 원천을 다시 본다. 첫 대조와 재확인 사이뿐 아니라
        // 각 대조 도중의 변경까지 포함하며, 무관한 방의 실제 손상은 그대로 남긴다.
        return mismatched.isEmpty() || onlyConcurrentChanges(
                mismatched, expectedSets.get(ExploreKeys.VISIBLE), changedSince);
    }

    /**
     * 대조가 보는 후보 한 줄.
     *
     * <p>구조만 볼 때는 앞의 다섯 값이면 충분하고({@code full} 이 {@code null}), 값까지 볼 때만
     * 투영 한 행을 통째로 들고 온다. 같은 코드가 두 깊이를 다루되 비싼 조회는 필요한 쪽만 치른다.
     */
    private record Candidate(UUID id, String category, String verificationType,
                             Double completionRate, Double retentionRate, Row full) {

        static Candidate of(Row row) {
            return new Candidate(row.id(), row.category(), row.verificationType(),
                    row.completionRate(), row.retentionRate(), row);
        }

        /** 그 정렬 ZSET 에 들어가야 하는가 — 비율 정렬은 표본이 있어야 들어간다. */
        boolean includedIn(ExploreSort sort) {
            return switch (sort) {
                case COMPLETION_RATE -> completionRate != null;
                case SUCCESS_FAIL_RATIO -> retentionRate != null;
                default -> true;
            };
        }
    }

    /** 아직 손상으로 확정하지 않고 양방향 차집합의 방 id만 모은다. */
    private static void collectMismatches(java.util.Set<String> actual,
                                          java.util.Set<String> expected,
                                          java.util.Set<String> mismatched) {
        for (String id : expected) if (!actual.contains(id)) mismatched.add(id);
        for (String id : actual) if (!expected.contains(id)) mismatched.add(id);
    }

    /**
     * 그 방의 <b>값</b>이 온전한가.
     *
     * <p>소속이 맞아도 값이 틀어질 수 있다 — 인기 점수만 날아가거나, HASH 의 숫자가 카드에
     * 잘못 찍히거나, 정렬 멤버에 박힌 값이 실제와 다른 경우다. 그 상태는 목록이 정상적으로
     * 응답하므로 아무도 모른다.
     *
     * <p><b>시간이 지나기만 해도 달라지는 값은 원천과 직접 비교하지 않는다.</b> 24시간 가입 수와
     * 그것으로 만든 인기 점수·POPULAR 멤버가 그렇다. 지금 원천에서 센 수와 몇 분 전에 투영한
     * 수가 다른 것은 <b>정상</b>이라, 그걸 불일치로 보면 평시에 전수 재구성과 503 이 돈다.
     * 대신 그 값들끼리의 <b>내부 일관성</b>을 본다 — 인기 점수와 HASH 의 가입 수는 같은 Lua
     * 한 번에 함께 쓰이므로, 시간이 얼마나 흘렀든 서로 어긋날 수 없다.
     *
     * <p>시간과 무관한 값(참여자 수·완주율·유지율과 그 정렬 멤버)은 <b>버전이 원천과 같을 때만</b>
     * 비교한다. 버전이 뒤처져 있다면 그 방은 아직 갱신이 날아오는 중이고, 값이 다른 것이 맞다.
     */
    private boolean valuesMatch(Row row, Map<Object, Object> hash, long projectedVersion) {
        // 값이 하나도 없는 HASH 는 <b>tombstone</b> 이다 — 후보에서 빠졌다가 막 돌아온 방이고,
        // 그 재투영이 아직 날아오는 중이다. 버전이 원천과 같은데도 비어 있다면 그건 손상이다.
        String joins = str(hash.get("recentJoins24h"));
        if (joins == null) {
            if (projectedVersion == row.version()) {
                log.debug("최신이라는 투영에 표시값이 없다 challengeId={}", row.id());
                return false;
            }
            return true;
        }

        // ── 인기: 점수와 표시 수가 서로 맞는가(시간과 무관) ──
        Double score = store.trendingScoreOf(ExploreKeys.TRENDING_ALL, row.id());
        if (score == null || ExploreRedisStore.recentJoinsOf(score) != Integer.parseInt(joins)) {
            log.debug("인기 점수가 표시값과 어긋난다 challengeId={} score={} hashJoins={}",
                    row.id(), score, joins);
            return false;
        }
        if (row.category() != null) {
            Double catScore = store.trendingScoreOf(ExploreKeys.trendingCategory(row.category()), row.id());
            // 둘이 어긋나면 홈과 카테고리 탭의 순위가 달라진다 — 같은 방이 자리마다 다르게 선다.
            if (!score.equals(catScore)) {
                log.debug("카테고리 인기 점수가 전체와 다르다 challengeId={} all={} cat={}",
                        row.id(), score, catScore);
                return false;
            }
        }

        if (projectedVersion != row.version()) return true;   // 아직 날아오는 중 — 값까지 묻지 않는다

        // ── 표시값: 이 방은 「이 원천 리비전 기준으로 최신」이라고 주장한다 ──
        if (!String.valueOf(row.participantCount()).equals(str(hash.get("participantCount")))
                || !sameRate(row.completionRate(), str(hash.get("completionRate")))
                || !sameRate(row.retentionRate(), str(hash.get("retentionRate")))) {
            log.debug("표시값이 원천과 다르다 challengeId={}", row.id());
            return false;
        }

        // ── 정렬 멤버에 박힌 값 ── POPULAR 은 시간으로 움직이므로 뺀다.
        for (ExploreSort sort : ExploreSort.values()) {
            if (sort == ExploreSort.POPULAR) continue;
            String expected = memberFor(sort, row);
            String actual = str(hash.get(MEMBER_FIELD + sort.name()));
            if (!java.util.Objects.equals(expected, actual)) {
                log.debug("정렬 멤버가 원천과 다르다 challengeId={} sort={}", row.id(), sort);
                return false;
            }
        }
        return true;
    }

    /** 표본 미달이면 원천은 {@code null} 이고 HASH 에는 그 필드가 아예 없어야 한다. */
    private static boolean sameRate(Double source, String projected) {
        return (source == null) ? projected == null : source.toString().equals(projected);
    }

    private static String str(Object value) {
        return (value == null) ? null : String.valueOf(value);
    }

    /**
     * 투영이 원천보다 뒤처져도 봐주는 폭.
     *
     * <p>5분 보정이 세 번 돌 시간이다. 이보다 더 벌어졌다면 그 방의 갱신은 「늦는 중」이 아니라
     * <b>유실됐다</b>고 봐야 한다. 더 좁히면 평시 갱신 지연이 전수 재구성을 부르고, 그 재구성은
     * 준비 상태를 내리므로 곧 503 이다.
     */
    private static final long STALE_PROJECTION_TOLERANCE_MICROS =
            java.time.Duration.ofMinutes(15).toMillis() * 1_000L;

    // ===== 투영 =====

    private void apply(Row row) {
        if (!row.visible()) {
            store.removeProjection(row.id(), row.version(), row.calcRevision(),
                    clearingSpecs(), allFilterSets(), allTrendingKeys());
            return;
        }

        Map<String, String> stats = new HashMap<>();
        List<String[]> sortedSpecs = new java.util.ArrayList<>();
        for (ExploreSort sort : ExploreSort.values()) {
            // 표본 미달 방은 memberFor 가 null 을 주므로 그 정렬의 ZSET 에서 빠진다 — 값 없는 방을
            // 최하위로 붙이면 "완주율 순"이라는 약속이 깨지기 때문이다(정책 §4.4).
            String newMember = memberFor(sort, row);
            sortedSpecs.add(new String[]{
                    ExploreKeys.sorted(sort), MEMBER_FIELD + sort.name(),
                    newMember == null ? "" : newMember});
        }

        stats.put("participantCount", String.valueOf(row.participantCount()));
        stats.put("recentJoins24h", String.valueOf(row.recentJoins()));
        if (row.completionRate() != null) stats.put("completionRate", row.completionRate().toString());
        if (row.retentionRate() != null) stats.put("retentionRate", row.retentionRate().toString());

        List<String> setsToAdd = new java.util.ArrayList<>();
        setsToAdd.add(ExploreKeys.VISIBLE);
        if (row.category() != null) setsToAdd.add(ExploreKeys.category(row.category()));
        if (row.verificationType() != null) setsToAdd.add(ExploreKeys.verifyType(row.verificationType()));
        // 카테고리·인증 방식은 바뀔 수 있다. 새 집합에 넣기만 하면 옛 필터 결과에 계속 뜬다.
        List<String> setsToRemove = new java.util.ArrayList<>();
        for (InterestCategory c : InterestCategory.values()) {
            if (!c.name().equals(row.category())) setsToRemove.add(ExploreKeys.category(c.name()));
        }
        for (String type : VERIFY_TYPES) {
            if (!type.equals(row.verificationType())) setsToRemove.add(ExploreKeys.verifyType(type));
        }

        Map<String, Double> trendingAdds = new java.util.LinkedHashMap<>();
        List<String> trendingRemoves = new java.util.ArrayList<>();
        double score = ExploreRedisStore.trendingScore(row.recentJoins(), row.lastJoinedMillis());
        trendingAdds.put(ExploreKeys.TRENDING_ALL, score);
        if (row.category() != null) trendingAdds.put(ExploreKeys.trendingCategory(row.category()), score);
        for (InterestCategory c : InterestCategory.values()) {
            if (!c.name().equals(row.category())) trendingRemoves.add(ExploreKeys.trendingCategory(c.name()));
        }

        store.applyProjection(row.id(), row.version(), row.calcRevision(), stats, sortedSpecs,
                setsToAdd, setsToRemove, trendingAdds, trendingRemoves);
    }

    /** 모든 정렬에서 빼는 지시 — 새 멤버가 빈 문자열이면 스크립트가 넣지 않고 지우기만 한다. */
    private static List<String[]> clearingSpecs() {
        List<String[]> specs = new java.util.ArrayList<>();
        for (ExploreSort sort : ExploreSort.values()) {
            specs.add(new String[]{ExploreKeys.sorted(sort), MEMBER_FIELD + sort.name(), ""});
        }
        return specs;
    }

    private static List<String> allFilterSets() {
        List<String> keys = new java.util.ArrayList<>();
        keys.add(ExploreKeys.VISIBLE);
        for (InterestCategory c : InterestCategory.values()) keys.add(ExploreKeys.category(c.name()));
        for (String type : VERIFY_TYPES) keys.add(ExploreKeys.verifyType(type));
        return keys;
    }

    private static List<String> allTrendingKeys() {
        List<String> keys = new java.util.ArrayList<>();
        keys.add(ExploreKeys.TRENDING_ALL);
        for (InterestCategory c : InterestCategory.values()) keys.add(ExploreKeys.trendingCategory(c.name()));
        return keys;
    }

    /**
     * 정렬 멤버 계산. 표본 미달이면 null 을 줘서 그 정렬의 ZSET 에 넣지 않는다.
     * 값 정규화는 SQL 경로가 쓰는 컬럼과 <b>같은 값</b>이어야 두 경로의 순서가 같아진다.
     */
    private String memberFor(ExploreSort sort, Row row) {
        // 동점 보정은 <b>생성 시각</b>이다(DB 설계 문서). 여기에 0 을 넣으면 값이 같은 방들이
        // UUID 순서로 줄을 서 「무작위로 보이는 순서」가 된다 — 참여자 수 같은 정렬은 동점이
        // 흔해서 목록 대부분이 그 상태가 된다. RECENT 는 그 자체가 생성 시각이라 보정이 없다.
        long createdSec = SortKeyCodec.ofInstantMillis(row.createdAtMillis()) / 1000L;
        return switch (sort) {
            case POPULAR -> SortKeyCodec.member(sort,
                    SortKeyCodec.ofCount(row.recentJoins()),
                    SortKeyCodec.ofInstantMillis(row.lastJoinedMillis()), row.id());
            case PARTICIPANTS -> SortKeyCodec.member(sort,
                    SortKeyCodec.ofCount(row.participantCount()), createdSec, row.id());
            case COMPLETION_RATE -> row.completionRate() == null ? null
                    : SortKeyCodec.member(sort, SortKeyCodec.ofRate(row.completionRate()), createdSec, row.id());
            case SUCCESS_FAIL_RATIO -> row.retentionRate() == null ? null
                    : SortKeyCodec.member(sort, SortKeyCodec.ofRate(row.retentionRate()), createdSec, row.id());
            case RECENT -> SortKeyCodec.member(sort,
                    SortKeyCodec.ofInstantMillis(row.createdAtMillis()), 0, row.id());
            case DEADLINE -> SortKeyCodec.member(sort,
                    SortKeyCodec.ofEpochDay(row.endEpochDay()), createdSec, row.id());
        };
    }

    // ===== 조회 =====

    private Row loadOne(UUID challengeId) {
        List<Row> rows = jdbc.query(SELECT_ROW + "WHERE c.id = ?",
                (rs, i) -> mapRow(rs), (Object) toBytes(challengeId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * @param version      <b>원천 리비전</b> — 이 방에 관한 원천이 마지막으로 움직인 시각.
     *                     투영의 주 버전이라, 「더 새 원천이 이긴다」가 문자 그대로 성립한다
     * @param calcRevision <b>계산 리비전</b> — 이 행을 읽은 기준 시각(DB 의 {@code NOW(6)}).
     *                     원천 리비전이 같은 두 계산의 순서를 가른다. 24시간 창도 이 시각으로
     *                     잘리므로, 시간만으로 값이 달라지는 경우에 정확히 대응한다
     */
    private record Row(UUID id, String category, String verificationType, boolean visible,
                       int participantCount, Long createdAtMillis, Long endEpochDay,
                       Double completionRate, Double retentionRate,
                       int recentJoins, Long lastJoinedMillis, long version, long calcRevision) {}

    private Row mapRow(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        boolean visible = "GROUP".equals(rs.getString("mode"))
                && "PUBLIC".equals(rs.getString("visibility"))
                && ("UPCOMING".equals(status) || "ACTIVE".equals(status))
                && rs.getTimestamp("deleted_at") == null;

        Timestamp created = rs.getTimestamp("created_at");
        Timestamp lastJoined = rs.getTimestamp("last_joined");
        Timestamp revision = rs.getTimestamp("source_revision");
        Timestamp readAt = rs.getTimestamp("read_at");
        java.sql.Date endDate = rs.getDate("end_date");
        long calcRevision = (readAt != null) ? micros(readAt) : System.currentTimeMillis() * 1_000L;

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
                lastJoined == null ? null : lastJoined.getTime(),
                // 원천 리비전이 곧 버전이다. 없을 수 없지만(c.updated_at 이 NOT NULL) 방어로 둔다.
                revision == null ? calcRevision : micros(revision),
                calcRevision);
    }

    /**
     * {@code DATETIME(6)} 을 <b>마이크로초</b>로 읽는다.
     *
     * <p>{@code Timestamp#getTime()} 은 밀리초라 MySQL 이 들고 있는 마이크로초를 버린다. 그러면
     * 같은 밀리초 안에 일어난 서로 다른 변경이 <b>한 리비전으로 겹치고</b>, 리비전으로 순서를
     * 정하겠다는 약속이 그 구간에서만 조용히 풀린다. 잘라 낼 이유가 없는 정밀도다.
     */
    private static long micros(Timestamp ts) {
        return (ts.getTime() / 1_000L) * 1_000_000L + ts.getNanos() / 1_000L;
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

    /**
     * 5분 보정이 다시 투영할 방만 골라 <b>한 번에</b> 읽어 반영한다.
     *
     * <p>예전에는 후보 전체를 훑고 방마다 {@code SELECT_ROW} 를 한 번씩 돌렸다. 방이 만 단위가
     * 되면 5분마다 만 번의 왕복이고, 그만큼 인기 하락 반영도 밀린다 — 스펙이 이 배치에 요구하는
     * 것은 「마지막 성공 실행 이후 <b>변경된</b> 방 재집계」다(백엔드 9).
     *
     * <p>다시 투영할 방은 두 갈래다.
     * <ol>
     *   <li><b>원천이 움직인 방</b> — 커밋 후 갱신이 유실된 구간을 따라잡는다. 시각 인덱스로 찾는다</li>
     *   <li><b>점수가 내려갈 수 있는 방</b> — 원천은 그대로인데 24시간 창이 흘러 값만 바뀐다.
     *       이 집합은 원천을 훑지 않고 <b>인기 ZSET 자신</b>에게 묻는다(가입 0건인 방은 내려갈 값이 없다)</li>
     * </ol>
     *
     * @param lookback 얼마나 거슬러 올라가 볼 것인가. {@code null} 이면 후보 전체를 본다(첫 회차).
     *                 <b>시각이 아니라 간격</b>이다 — 기준 시각은 DB 시계로만 만든다({@link #CUTOFF})
     * @return 다시 투영한 방 수
     */
    public int reprojectChanged(java.time.Duration lookback) {
        java.util.LinkedHashSet<UUID> targets = new java.util.LinkedHashSet<>();
        if (lookback == null) {
            targets.addAll(visibleCandidateIds());
        } else {
            targets.addAll(changedSince(lookback));
            for (String hex : store.membersWithRecentJoins()) targets.add(ExploreKeys.fromHex(hex));
        }
        if (targets.isEmpty()) return 0;

        // IN 목록을 통째로 넘기면 플랜이 무너지고 패킷도 커진다 — 묶음으로 나눠 읽는다.
        List<UUID> all = new ArrayList<>(targets);
        for (int from = 0; from < all.size(); from += CHUNK) {
            List<UUID> chunk = all.subList(from, Math.min(from + CHUNK, all.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            Object[] args = chunk.stream().map(ExploreIndexer::toBytes).toArray();
            for (Row row : jdbc.query(SELECT_ROW + "WHERE c.id IN (" + placeholders + ")",
                    (rs, i) -> mapRow(rs), args)) {
                apply(row);
            }
        }
        return all.size();
    }

    /** 한 번에 읽는 방 수. 크게 잡으면 IN 목록이 길어져 플랜이 나빠지고, 작으면 왕복이 는다. */
    private static final int CHUNK = 500;

    /**
     * 그 시각 이후 원천이 움직인 후보 방.
     *
     * <p>신호가 넷이고, <b>{@link #SELECT_ROW} 의 원천 리비전과 정확히 같은 넷</b>이어야 한다 —
     * 방 설정({@code challenges}), 멤버십 변화와 판정 확정({@code challenge_members}),
     * 가입 사건({@code challenge_join_events}), 통계({@code challenge_stats}).
     *
     * <p>리비전에는 들어 있는데 여기서 빠진 신호가 있으면 그 방은 <b>영영 따라잡히지 않는다</b>:
     * 리비전은 올라갔는데 다시 투영할 대상으로는 뽑히지 않으니 Redis 버전이 원천보다 뒤처진 채
     * 굳고, 그 차이가 허용 오차를 넘는 순간 대조가 「투영이 유실됐다」로 읽어 전수 재구성과
     * 503 을 부른다. 실제로 {@code challenge_stats} 가 그렇게 빠져 있었다.
     *
     * <p>통계 재계산이 쓰는 조건과는 <b>일부러 다르다.</b> 그쪽은 「통계를 다시 셀 방」을 고르므로
     * 통계 자신의 변경 시각을 넣으면 제 꼬리를 무는 셈이 된다. 여기는 「이미 달라진 값을 파생에
     * 옮길 방」을 고르는 자리라 그 신호가 있어야 한다.
     */
    private List<UUID> changedSince(java.time.Duration lookback) {
        long seconds = Math.max(0L, lookback.toSeconds());
        List<UUID> ids = new ArrayList<>();
        jdbc.query("SELECT c.id FROM challenges c WHERE " + VISIBLE_CONDITION + " AND ("
                        + "     c.updated_at >= " + CUTOFF
                        + "  OR EXISTS (SELECT 1 FROM challenge_members m "
                        + "              WHERE m.challenge_id = c.id AND m.updated_at >= " + CUTOFF + ") "
                        + "  OR EXISTS (SELECT 1 FROM challenge_join_events e "
                        + "              WHERE e.challenge_id = c.id AND e.joined_at >= " + CUTOFF + ") "
                        + "  OR EXISTS (SELECT 1 FROM challenge_stats s2 "
                        + "              WHERE s2.challenge_id = c.id AND s2.updated_at >= " + CUTOFF + "))",
                rs -> { ids.add(toUuid(rs.getBytes(1))); }, seconds, seconds, seconds, seconds);
        return ids;
    }

    /**
     * Redis 대조가 끝난 뒤 불일치한 방만 원천에서 재확인한다.
     *
     * <p>기준은 대조 시작 전 DB 시각에서 유예 폭을 뺀 값이다. 재확인 시점의 NOW()로 다시
     * 만들면 오래 걸린 대조 도중의 변경을 놓친다. 비공개 전환도 확인해야 하므로 노출 조건을
     * 걸지 않고, 방마다 왕복하지 않도록 최대 CHUNK개씩 읽는다.
     */
    private boolean onlyConcurrentChanges(java.util.Set<String> mismatched,
                                           java.util.Set<String> sourceSnapshotIds,
                                           String changedSince) {
        List<String> ids = new ArrayList<>(mismatched);
        java.util.Set<String> existing = new java.util.HashSet<>();
        String cutoff = "CAST(? AS DATETIME(6))";
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<String> chunk = ids.subList(from, Math.min(from + CHUNK, ids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>(java.util.Collections.nCopies(4, changedSince));
            for (String hex : chunk) args.add(toBytes(ExploreKeys.fromHex(hex)));
            jdbc.query("SELECT c.id, (c.updated_at >= " + cutoff
                            + " OR EXISTS (SELECT 1 FROM challenge_members m "
                            + "             WHERE m.challenge_id = c.id AND m.updated_at >= " + cutoff + ")"
                            + " OR EXISTS (SELECT 1 FROM challenge_join_events e "
                            + "             WHERE e.challenge_id = c.id AND e.joined_at >= " + cutoff + ")"
                            + " OR EXISTS (SELECT 1 FROM challenge_stats s2 "
                            + "             WHERE s2.challenge_id = c.id AND s2.updated_at >= " + cutoff + ")"
                            + ") AS changed FROM challenges c WHERE c.id IN (" + placeholders + ")",
                    rs -> {
                        String hex = ExploreKeys.hex(toUuid(rs.getBytes("id")));
                        existing.add(hex);
                        if (rs.getBoolean("changed")) mismatched.remove(hex);
                    }, args.toArray());
        }
        // 스냅샷에 있던 행이 지금 없다면 대조 도중 하드 삭제된 것이다. 처음부터 원천에 없던
        // Redis 유령은 제외하지 않는다 — 그쪽은 실제 손상이므로 복구되어야 한다.
        mismatched.removeIf(hex -> sourceSnapshotIds.contains(hex) && !existing.contains(hex));
        if (mismatched.isEmpty()) return true;
        log.warn("원천 재확인 후에도 파생이 다른 방 {}건 (대조한 불일치 {}건)", mismatched.size(), ids.size());
        return false;
    }

    /**
     * 「지금으로부터 몇 초 전」을 <b>DB 시계로</b> 만든다.
     *
     * <p>기준 시각을 애플리케이션에서 {@code Instant} 로 만들어 넘기면 안 된다. 그 값은 JVM 의
     * 시계와 기본 시간대를 타는데, 드라이버가 {@code DATETIME} 을 읽고 쓸 때 쓰는 시간대와
     * 어긋나면 <b>조용히 몇 시간이 밀린다</b> — 조건이 늘 거짓이 되어 증분 보정이 아무 방도
     * 고르지 못하고, 그 사실이 오류 하나 없이 「바뀐 방 0건」으로만 보인다.
     *
     * <p>시각 비교는 전부 원천이 가진 시계 안에서 끝내고, 밖에서는 <b>얼마나 거슬러 올라갈지</b>만
     * 넘긴다. 간격은 두 {@code Instant} 의 차이라 시간대와 무관하다.
     */
    private static final String CUTOFF = "(NOW(6) - INTERVAL ? SECOND) ";

    /**
     * 대조가 <b>봐주는</b> 폭.
     *
     * <p>원천 스냅샷과 Redis 는 같은 순간을 보지 않는다. 커밋 직후 투영은 비동기라 방금 공개된
     * 방이 아직 안 들어와 있을 수 있고, MySQL 을 읽고 Redis 를 읽는 사이에 새 커밋이 끼어들 수도
     * 있다. 그 정상 상태를 「손상」으로 읽으면 <b>평시 트래픽만으로 준비 상태가 내려가고 탐색이
     * 503 을 낸다</b> — 대조가 고치라고 있는 것보다 더 큰 고장을 스스로 만드는 셈이다.
     *
     * <p>이 폭 안에서 움직인 방은 어차피 5분 보정이 <b>매 회차 다시 투영한다.</b> 그러니 봐줘도
     * 잃는 것이 없다 — 늦어도 다음 회차에 제자리를 찾는다. 이 폭 밖의 어긋남만 진짜 유실이다.
     */
    private static final java.time.Duration SETTLE = java.time.Duration.ofMinutes(2);

    /** 전체 후보의 id 목록 — 유령 제거가 「지금 살아 있는 것」의 기준으로 쓴다. */
    public List<UUID> visibleCandidateIds() {
        List<UUID> ids = new ArrayList<>();
        jdbc.query("SELECT c.id FROM challenges c WHERE " + VISIBLE_CONDITION,
                rs -> { ids.add(toUuid(rs.getBytes(1))); });
        return ids;
    }
}
