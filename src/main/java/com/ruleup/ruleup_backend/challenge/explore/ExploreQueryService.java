package com.ruleup.ruleup_backend.challenge.explore;

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

    private final JdbcTemplate jdbc;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final MyMembershipReader myMembershipReader;
    private final MeterRegistry meterRegistry;

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
        ExploreCursor cursor = ExploreCursor.decode(cursorRaw, sort);
        int size = normalizeSize(sizeRaw);
        List<String> categories = parseCategories(categoriesCsv);
        String verification = parseVerifyType(verifyType);
        Tier myTier = displayTier(userId);
        // 내가 이미 들어가 있는 방(내가 만든 방 포함)은 목록에서 빼지 않고 joined 로만 구분한다.
        Set<UUID> myChallengeIds = myMembershipReader.activeChallengeIds(userId);

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT c.id, c.title, c.ai_title, c.moderation_title, c.image_url, c.moderation_image, " +
                        "       c.category, c.verification_type, c.status, c.participant_count, c.capacity, " +
                        "       c.min_tier, c.start_date, c.end_date, c.created_at, " +
                        "       s.completion_rate, s.retention_rate, s.recent_joins_24h, s.last_joined_at_24h " +
                        "FROM challenges c JOIN challenge_stats s ON s.challenge_id = c.id " +
                        // ① 노출 제외 — 후보 조건을 맨 앞에 고정
                        "WHERE c.mode = 'GROUP' AND c.visibility = 'PUBLIC' " +
                        "  AND c.status IN ('UPCOMING', 'ACTIVE') AND c.deleted_at IS NULL " +
                        // 숨기는 근거는 신고가 아니라 **차단**이다. 신고 이력으로 걸면 차단을 해제해도
                        // 방이 다시 나타나지 않는다 — 해제의 의미가 "이제 보여도 괜찮다"이므로 어긋난다.
                        "  AND NOT EXISTS (SELECT 1 FROM user_blocks b " +
                        "                  WHERE b.blocker_id = ? AND b.target_type = 'CHALLENGE' " +
                        "                    AND b.target_id = c.id) ");
        args.add(toBytes(userId));

        // ② 필터 — 서로 AND, 카테고리끼리만 OR
        if (!categories.isEmpty()) {
            sql.append("AND c.category IN (")
                    .append(String.join(",", java.util.Collections.nCopies(categories.size(), "?")))
                    .append(") ");
            args.addAll(categories);
        }
        if (verification != null) {
            sql.append("AND c.verification_type = ? ");
            args.add(verification);
        }
        if (Boolean.TRUE.equals(eligibleOnly)) {
            sql.append("AND (c.min_tier IS NULL OR FIELD(c.min_tier, ").append(TIER_ORDER).append(") <= ?) ");
            args.add(tierRank(myTier));
        }

        // ③ 정렬 — 표본 미달 제외는 해당 지표 정렬일 때만
        if (sort.extraCondition() != null) sql.append("AND ").append(sort.extraCondition()).append(' ');
        appendKeyset(sql, args, sort, cursor);
        sql.append("ORDER BY ").append(sort.orderBy()).append(" LIMIT ?");
        args.add(size + 1);   // hasNext 판정용 한 건 더

        List<Row> rows = jdbc.query(sql.toString(), (rs, i) -> mapRow(rs), args.toArray());

        boolean hasNext = rows.size() > size;
        List<Row> page = hasNext ? rows.subList(0, size) : rows;
        LocalDate today = LocalDate.now(KST);
        List<ExploreResponse.Item> items =
                page.stream().map(r -> toItem(r, myTier, myChallengeIds, today)).toList();
        String nextCursor = hasNext ? cursorOf(sort, page.get(page.size() - 1)).encode() : null;
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
        return new ExploreCursor(sort, primary, secondary, r.id);
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
