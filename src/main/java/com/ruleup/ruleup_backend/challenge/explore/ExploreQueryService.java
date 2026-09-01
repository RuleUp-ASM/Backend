package com.ruleup.ruleup_backend.challenge.explore;

import com.ruleup.ruleup_backend.challenge.explore.store.ExploreCircuitBreaker;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexer;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreKeys;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreRedisStore;
import com.ruleup.ruleup_backend.challenge.explore.store.SortKeyCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.ruleup.ruleup_backend.challenge.dto.ExploreResponse;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 목록 조회 (탐색 백엔드 테크스펙 §11-3).
 *
 * <p>처리 순서는 <b>① 노출 제외 → ② 필터 AND → ③ 정렬</b>이다. 노출 제외를 가장 먼저 두는 이유는
 * 비공개·솔로 방이 어떤 필터 조합으로도 새어 나오면 안 되기 때문이다 — 후보 조건을 WHERE 최상단에
 * 고정해 두면 뒤에 무엇이 붙어도 은닉이 깨지지 않는다.
 *
 * <p>지표는 매번 계산하지 않고 {@code challenge_stats} 에서 읽는다. 완주율·유지율 정렬일 때만
 * 표본 미달 방을 제외한다(정책 §4.4).
 *
 * <h4>두 경로가 같은 순서를 낸다</h4>
 * 정상 경로는 <b>Redis</b> 다 — 정렬 ZSET 을 사전순으로 훑고 필터 SET 으로 교차한다. Redis 가
 * 죽었거나 워밍업 전이면 <b>MySQL</b> keyset 으로 내려간다. 두 경로의 순서가 반드시 같아야 하므로
 * ZSET 멤버 인코딩({@link SortKeyCodec})이 {@code ORDER BY} 와 같은 전순서를 만들도록 맞춰 뒀다.
 *
 * <h4>사용자 컨텍스트는 어느 경로에서도 MySQL 이다</h4>
 * 차단 목록과 티어 자격은 <b>보는 사람마다 다르다</b>. 공용 인덱스에 넣을 수 없고 넣어서도 안 되므로
 * (남의 화면에 새어 나간다), Redis 는 순서와 노출 후보까지만 정하고 최종 행은 MySQL 에서 읽는다.
 */
@Service
@RequiredArgsConstructor
public class ExploreQueryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 20;

    /** 인기 2차 키의 NULL 대체값. 커서에 담는 문자열과 같은 표기여야 한다. */
    private static final String EPOCH_LITERAL = "1970-01-01 00:00:00";
    private static final String EPOCH = "'" + EPOCH_LITERAL + "'";

    /** 티어 비교는 문자열이 아니라 순서로 해야 한다 — MySQL ENUM 을 문자열과 비교하면 사전순이 된다. */
    private static final String TIER_ORDER = "'BRONZE','SILVER','GOLD','DIAMOND','RUBY'";

    /** 두 경로가 같은 컬럼을 읽는다 — 어느 쪽으로 내려가든 카드 표시값이 달라지면 안 된다. */
    private static final String SELECT_COLUMNS =
            "SELECT c.id, c.title, c.ai_title, c.moderation_title, c.image_url, c.moderation_image, " +
            "       c.category, c.verification_type, c.status, c.participant_count, c.capacity, " +
            "       c.min_tier, c.start_date, c.end_date, c.created_at, " +
            "       s.completion_rate, s.retention_rate, s.recent_joins_24h, s.last_joined_at_24h " +
            "FROM challenges c JOIN challenge_stats s ON s.challenge_id = c.id ";

    /** Redis 후보를 한 번에 읽어올 크기. 필터로 걸러지는 비율을 감안해 페이지보다 넉넉히 잡는다. */
    private static final int SCAN_CHUNK = 100;
    /** 스캔 상한 — 필터가 극단적으로 선택적일 때 한 요청이 ZSET 전체를 훑지 않게 막는다. */
    private static final int MAX_SCANS = 10;

    private final JdbcTemplate jdbc;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final MyMembershipReader myMembershipReader;
    private final MeterRegistry meterRegistry;
    private final ExploreRedisStore store;
    private final ExploreCircuitBreaker circuit;
    private final ExploreIndexer indexer;

    @Transactional(readOnly = true)
    public ExploreResponse explore(UUID userId, String categoriesCsv, String verifyType,
                                   Boolean eligibleOnly, String sortRaw, String cursorRaw, Integer sizeRaw) {
        Timer.Sample sample = Timer.start(meterRegistry);
        ExploreSort sort = null;
        try {
            sort = ExploreSort.parse(sortRaw);
            return executeExplore(userId, categoriesCsv, verifyType, eligibleOnly, cursorRaw, sizeRaw, sort);
        } finally {
            sample.stop(Timer.builder("explore_p95")
                    .description("챌린지 탐색 목록 응답 시간")
                    .publishPercentiles(0.95)
                    .tag("sort", sort == null ? "INVALID" : sort.name())
                    .register(meterRegistry));
        }
    }

    private ExploreResponse executeExplore(UUID userId, String categoriesCsv, String verifyType,
                                           Boolean eligibleOnly, String cursorRaw, Integer sizeRaw,
                                           ExploreSort sort) {
        int size = normalizeSize(sizeRaw);
        List<String> categories = parseCategories(categoriesCsv);
        String verification = parseVerifyType(verifyType);
        Tier myTier = displayTier(userId);
        // 내가 이미 들어가 있는 방(내가 만든 방 포함)은 목록에서 빼지 않고 joined 로만 구분한다.
        Set<UUID> myChallengeIds = myMembershipReader.activeChallengeIds(userId);

        // 경로를 먼저 정하고 그 경로의 커서만 받는다 — 경로가 바뀌면 커서의 의미가 달라진다.
        ExploreDataSource source = chooseSource();
        ExploreCursor cursor = ExploreCursor.decode(cursorRaw, sort, source);
        Query query = new Query(userId, categories, verification, eligibleOnly, sort, size,
                myTier, myChallengeIds);

        if (source == ExploreDataSource.MYSQL) return fromMysql(query, cursor);
        try {
            return fromRedis(query, cursor);
        } catch (RuntimeException e) {
            circuit.recordFailure(e);                     // 연속 실패면 회로가 열린다
            // 커서를 들고 있었다면 이어 붙일 수 없다 — 첫 페이지부터 다시 받게 한다.
            if (cursor != null) throw new BusinessException(ErrorCode.CURSOR_INVALID);
            return fromMysql(query, null);
        }
    }

    /** 한 요청이 쓰는 값 묶음 — 경로가 둘이라 인자 목록이 길어지는 것을 막는다. */
    private record Query(UUID userId, List<String> categories, String verification,
                         Boolean eligibleOnly, ExploreSort sort, int size,
                         Tier myTier, Set<UUID> myChallengeIds) {}

    /**
     * 워밍업 전이면 Redis 를 쓰지 않는다 — 반쯤 찬 인덱스로 목록을 내리면 방이 사라진 것처럼 보인다.
     * 회로가 열려 있으면 묻지도 않고 SQL 이다.
     */
    private ExploreDataSource chooseSource() {
        if (circuit.isOpen()) return ExploreDataSource.MYSQL;
        try {
            return indexer.isWarmed() ? ExploreDataSource.REDIS : ExploreDataSource.MYSQL;
        } catch (RuntimeException e) {
            // 여기서 회로에 세지 않으면 요청마다 Redis 타임아웃을 그대로 물게 된다.
            circuit.recordFailure(e);
            return ExploreDataSource.MYSQL;
        }
    }

    // =====================================================================
    // Redis 경로
    // =====================================================================

    /**
     * ZSET 을 사전순으로 훑으며 필터 SET 으로 걸러 후보 순서를 만들고, 그 순서대로 MySQL 에서
     * 행을 읽는다.
     *
     * <p>페이지가 찰 때까지 청크를 반복해 읽는 이유는 차단·티어 필터가 <b>Redis 쪽에서 걸러지지
     * 않기</b> 때문이다. 상한({@code MAX_SCANS})을 둬서 극단적으로 선택적인 필터 조합이 ZSET 전체를
     * 훑지 않게 막는다.
     *
     * <p><b>상한에 걸렸을 때 목록을 끝내지 않는 것이 중요하다.</b> 모은 것만 내리고 {@code hasNext}
     * 를 false 로 두면 뒤에 남아 있는 방이 조용히 사라진다 — 사용자에게는 "그게 전부"로 보이고
     * 서버에는 아무 신호도 남지 않는다. 그래서 <b>훑다 만 지점</b>을 커서로 내려 다음 요청이
     * 이어받게 한다. 그 페이지가 짧을 수는 있어도 방이 없어지지는 않는다.
     */
    private ExploreResponse fromRedis(Query q, ExploreCursor cursor) {
        List<Row> kept = new ArrayList<>();
        List<String> keptMembers = new ArrayList<>();
        String afterMember = (cursor == null) ? null : cursor.primary();
        boolean zsetExhausted = false;

        for (int scan = 0; scan < MAX_SCANS && kept.size() <= q.size(); scan++) {
            List<String> members = store.sortedRange(q.sort(), afterMember, SCAN_CHUNK);
            if (members.size() < SCAN_CHUNK) zsetExhausted = true;
            if (members.isEmpty()) break;
            afterMember = members.getLast();

            List<UUID> ids = members.stream().map(m -> SortKeyCodec.idOf(m, q.sort())).toList();
            List<UUID> candidates = applyRedisFilters(q, ids);
            if (candidates.isEmpty()) continue;

            // 최종 행과 사용자 컨텍스트(차단·티어)는 MySQL 이 판정한다.
            Map<UUID, Row> rows = fetchRows(q, candidates);
            for (int i = 0; i < ids.size() && kept.size() <= q.size(); i++) {
                Row row = rows.get(ids.get(i));
                if (row == null) continue;
                kept.add(row);
                keptMembers.add(members.get(i));
            }
        }

        if (kept.size() > q.size()) {
            List<Row> page = kept.subList(0, q.size());
            return render(q, page, redisCursor(q.sort(), keptMembers.get(q.size() - 1)), true);
        }
        // 페이지를 못 채웠다. ZSET 을 끝까지 봤으면 정말 끝이고, 상한에 걸린 것이면 아직 남아 있다.
        boolean hasNext = !zsetExhausted && afterMember != null;
        return render(q, kept, hasNext ? redisCursor(q.sort(), afterMember) : null, hasNext);
    }

    /** 멤버 문자열 하나가 커서의 전부다 — id 는 거기서 꺼내 검증용으로 함께 싣는다. */
    private String redisCursor(ExploreSort sort, String member) {
        return new ExploreCursor(sort, ExploreDataSource.REDIS, member, null,
                SortKeyCodec.idOf(member, sort)).encode();
    }

    /** 노출 후보·카테고리·인증 방식을 Redis 집합으로 교차한다. 카테고리끼리만 OR, 나머지는 AND. */
    private List<UUID> applyRedisFilters(Query q, List<UUID> ids) {
        List<Boolean> visible = store.areMembers(ExploreKeys.VISIBLE, ids);

        List<Boolean> categoryHit = null;
        if (!q.categories().isEmpty()) {
            categoryHit = new ArrayList<>(java.util.Collections.nCopies(ids.size(), Boolean.FALSE));
            for (String category : q.categories()) {
                List<Boolean> hit = store.areMembers(ExploreKeys.category(category), ids);
                for (int i = 0; i < ids.size(); i++) {
                    if (Boolean.TRUE.equals(hit.get(i))) categoryHit.set(i, Boolean.TRUE);
                }
            }
        }
        List<Boolean> verifyHit = (q.verification() == null) ? null
                : store.areMembers(ExploreKeys.verifyType(q.verification()), ids);

        List<UUID> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            if (!Boolean.TRUE.equals(visible.get(i))) continue;
            if (categoryHit != null && !Boolean.TRUE.equals(categoryHit.get(i))) continue;
            if (verifyHit != null && !Boolean.TRUE.equals(verifyHit.get(i))) continue;
            out.add(ids.get(i));
        }
        return out;
    }

    /**
     * 후보 id 들의 행을 읽는다. <b>노출 조건을 여기서도 다시 건다</b> — 인덱스가 아직 따라오지
     * 못한 순간에 비공개로 바뀐 방이 새어 나가면 안 되기 때문이다(존재 은닉은 최후 방어선이 필요하다).
     */
    private Map<UUID, Row> fetchRows(Query q, List<UUID> ids) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
                .append("WHERE c.mode = 'GROUP' AND c.visibility = 'PUBLIC' ")
                .append("  AND c.status IN ('UPCOMING', 'ACTIVE') AND c.deleted_at IS NULL ")
                .append("  AND NOT EXISTS (SELECT 1 FROM user_blocks b ")
                .append("                  WHERE b.blocker_id = ? AND b.target_type = 'CHALLENGE' ")
                .append("                    AND b.target_id = c.id) ");
        args.add(toBytes(q.userId()));

        if (Boolean.TRUE.equals(q.eligibleOnly())) {
            sql.append("AND (c.min_tier IS NULL OR FIELD(c.min_tier, ").append(TIER_ORDER).append(") <= ?) ");
            args.add(tierRank(q.myTier()));
        }
        if (q.sort().extraCondition() != null) sql.append("AND ").append(q.sort().extraCondition()).append(' ');

        sql.append("AND c.id IN (")
                .append(String.join(",", java.util.Collections.nCopies(ids.size(), "?")))
                .append(") ");
        ids.forEach(id -> args.add(toBytes(id)));

        Map<UUID, Row> byId = new java.util.HashMap<>();
        jdbc.query(sql.toString(), rs -> { Row row = mapRow(rs); byId.put(row.id, row); },
                args.toArray());
        return byId;
    }

    // =====================================================================
    // MySQL 폴백 경로
    // =====================================================================

    private ExploreResponse fromMysql(Query q, ExploreCursor cursor) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
                // ① 노출 제외 — 후보 조건을 맨 앞에 고정
                .append("WHERE c.mode = 'GROUP' AND c.visibility = 'PUBLIC' ")
                .append("  AND c.status IN ('UPCOMING', 'ACTIVE') AND c.deleted_at IS NULL ")
                // 숨기는 근거는 신고가 아니라 **차단**이다. 신고 이력으로 걸면 차단을 해제해도
                // 방이 다시 나타나지 않는다 — 해제의 의미가 "이제 보여도 괜찮다"이므로 어긋난다.
                .append("  AND NOT EXISTS (SELECT 1 FROM user_blocks b ")
                .append("                  WHERE b.blocker_id = ? AND b.target_type = 'CHALLENGE' ")
                .append("                    AND b.target_id = c.id) ");
        args.add(toBytes(q.userId()));

        // ② 필터 — 서로 AND, 카테고리끼리만 OR
        if (!q.categories().isEmpty()) {
            sql.append("AND c.category IN (")
                    .append(String.join(",", java.util.Collections.nCopies(q.categories().size(), "?")))
                    .append(") ");
            args.addAll(q.categories());
        }
        if (q.verification() != null) {
            sql.append("AND c.verification_type = ? ");
            args.add(q.verification());
        }
        if (Boolean.TRUE.equals(q.eligibleOnly())) {
            sql.append("AND (c.min_tier IS NULL OR FIELD(c.min_tier, ").append(TIER_ORDER).append(") <= ?) ");
            args.add(tierRank(q.myTier()));
        }

        // ③ 정렬 — 표본 미달 제외는 해당 지표 정렬일 때만
        if (q.sort().extraCondition() != null) sql.append("AND ").append(q.sort().extraCondition()).append(' ');
        appendKeyset(sql, args, q.sort(), cursor);
        sql.append("ORDER BY ").append(q.sort().orderBy()).append(" LIMIT ?");
        args.add(q.size() + 1);   // hasNext 판정용 한 건 더

        List<Row> rows = jdbc.query(sql.toString(), (rs, i) -> mapRow(rs), args.toArray());

        boolean hasNext = rows.size() > q.size();
        List<Row> page = hasNext ? rows.subList(0, q.size()) : rows;
        String nextCursor = hasNext ? cursorOf(q.sort(), page.get(page.size() - 1)).encode() : null;
        return render(q, page, nextCursor, hasNext);
    }

    private ExploreResponse render(Query q, List<Row> page, String nextCursor, boolean hasNext) {
        LocalDate today = LocalDate.now(KST);
        List<ExploreResponse.Item> items =
                page.stream().map(r -> toItem(r, q.myTier(), q.myChallengeIds(), today)).toList();
        return new ExploreResponse(items, nextCursor, hasNext);
    }

    /** 커서 이후 구간만 남기는 keyset 조건. 정렬 방향에 따라 부등호가 뒤집힌다. */
    private void appendKeyset(StringBuilder sql, List<Object> args, ExploreSort sort, ExploreCursor cursor) {
        if (cursor == null) return;
        String cmp = sort.ascending() ? ">" : "<";
        String primary = sort.primary();
        String secondary = sort.secondary();

        if (secondary == null) {
            sql.append("AND (").append(primary).append(' ').append(cmp).append(" ? OR (")
                    .append(primary).append(" = ? AND c.id ").append(cmp).append(" ?)) ");
            args.add(cursor.primary());
            args.add(cursor.primary());
            args.add(toBytes(cursor.id()));
            return;
        }
        // 3중 키(인기: 참여 수 → 마지막 참여 시각 → id). NULL 시각은 가장 오래된 값으로 취급한다.
        // 기본값 표기는 커서에 담는 문자열과 정확히 같아야 한다 — '1970-01-01' 처럼 짧게 쓰면
        // 문자열 비교로 떨어질 때 경계 행이 다음 페이지에 다시 끼어든다.
        String sec = "COALESCE(" + secondary + ", " + EPOCH + ")";
        sql.append("AND (").append(primary).append(' ').append(cmp).append(" ? OR (")
                .append(primary).append(" = ? AND (").append(sec).append(' ').append(cmp).append(" ? OR (")
                .append(sec).append(" = ? AND c.id ").append(cmp).append(" ?)))) ");
        args.add(cursor.primary());
        args.add(cursor.primary());
        args.add(cursor.secondary());
        args.add(cursor.secondary());
        args.add(toBytes(cursor.id()));
    }

    private ExploreCursor cursorOf(ExploreSort sort, Row r) {
        String primary = switch (sort) {
            case POPULAR -> String.valueOf(r.recentJoins24h);
            case PARTICIPANTS -> String.valueOf(r.participantCount);
            case COMPLETION_RATE -> String.valueOf(r.completionRate);
            case SUCCESS_FAIL_RATIO -> String.valueOf(r.retentionRate);
            case RECENT -> r.createdAt;
            case DEADLINE -> r.endDate;
        };
        String secondary = (sort == ExploreSort.POPULAR)
                ? (r.lastJoinedAt24h != null ? r.lastJoinedAt24h : EPOCH_LITERAL) : null;
        return new ExploreCursor(sort, ExploreDataSource.MYSQL, primary, secondary, r.id);
    }

    private ExploreResponse.Item toItem(Row r, Tier myTier, Set<UUID> myChallengeIds, LocalDate today) {
        boolean upcoming = "UPCOMING".equals(r.status);
        boolean full = r.capacity != null && r.participantCount >= r.capacity;
        boolean eligible = r.minTier == null || myTier.ordinal() >= Tier.valueOf(r.minTier).ordinal();
        return new ExploreResponse.Item(
                r.id.toString(),
                // 심사 중·거부면 AI 임시 제목 / 기본 이미지로 대체 표시
                publicVisible(r.moderationTitle) ? r.title : r.aiTitle,
                publicVisible(r.moderationImage) ? r.imageUrl : null,
                r.category,
                r.verificationType,
                upcoming,
                r.participantCount,
                r.capacity,
                full,
                r.minTier,
                eligible,
                myChallengeIds.contains(r.id),
                // 시작 전 방은 진행 지표 자체가 없다
                upcoming ? null : r.completionRate,
                upcoming ? null : r.retentionRate,
                (int) ChronoUnit.DAYS.between(today, LocalDate.parse(r.endDate)),
                r.startDate, r.endDate, r.createdAt);
    }

    /** 심사 상태가 공개 가능한 값인가 — EXEMPT·APPROVED 만 원본을 보여준다. */
    private boolean publicVisible(String moderationStatus) {
        return "EXEMPT".equals(moderationStatus) || "APPROVED".equals(moderationStatus)
                || "NONE".equals(moderationStatus);
    }

    // ===== 입력 정규화 =====

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) return DEFAULT_SIZE;
        return Math.min(size, MAX_SIZE);
    }

    private List<String> parseCategories(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> codes = Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).toList();
        if (!InterestCategory.allValid(codes)) throw new BusinessException(ErrorCode.INVALID_FILTER_VALUE);
        return codes;
    }

    private String parseVerifyType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toUpperCase();
        if (!"AUTO".equals(v) && !"MANUAL".equals(v)) throw new BusinessException(ErrorCode.INVALID_FILTER_VALUE);
        return v;
    }

    private Tier displayTier(UUID userId) {
        Tier tier = scoreSummaryRepository.findById(userId).map(s -> s.getDisplayTier()).orElse(Tier.BRONZE);
        return (tier == Tier.UNRANKED) ? Tier.BRONZE : tier;
    }

    private int tierRank(Tier tier) {
        return tier.ordinal();   // UNRANKED=0 이므로 BRONZE=1 … RUBY=5 — FIELD() 결과와 같은 체계
    }

    // ===== 행 매핑 =====

    private record Row(UUID id, String title, String aiTitle, String moderationTitle,
                       String imageUrl, String moderationImage, String category, String verificationType,
                       String status, int participantCount, Integer capacity, String minTier,
                       String startDate, String endDate, String createdAt,
                       Double completionRate, Double retentionRate,
                       int recentJoins24h, String lastJoinedAt24h) {}

    private Row mapRow(ResultSet rs) throws SQLException {
        return new Row(
                toUuid(rs.getBytes("id")),
                rs.getString("title"), rs.getString("ai_title"), rs.getString("moderation_title"),
                rs.getString("image_url"), rs.getString("moderation_image"),
                rs.getString("category"), rs.getString("verification_type"), rs.getString("status"),
                rs.getInt("participant_count"), (Integer) rs.getObject("capacity"), rs.getString("min_tier"),
                String.valueOf(rs.getDate("start_date")), String.valueOf(rs.getDate("end_date")),
                String.valueOf(rs.getTimestamp("created_at")),
                (Double) rs.getObject("completion_rate", Double.class),
                (Double) rs.getObject("retention_rate", Double.class),
                rs.getInt("recent_joins_24h"),
                rs.getTimestamp("last_joined_at_24h") == null
                        ? null : String.valueOf(rs.getTimestamp("last_joined_at_24h")));
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
}
