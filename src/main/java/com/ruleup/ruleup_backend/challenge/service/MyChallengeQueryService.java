package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.dto.ChallengeListResponse;
import com.ruleup.ruleup_backend.challenge.domain.TargetModerationStatus;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Live rooms and complete deletion snapshots share the same response and cursor. */
@Service
@RequiredArgsConstructor
public class MyChallengeQueryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    /** 살아 있는 방의 이탈 유형(enum('LEAVE','KICK'))을 API 계약 값으로 옮긴다. */
    private static final String LIVE_LEAVE = "LEAVE";
    private static final String LIVE_KICK = "KICK";

    /** 테이블 콜레이션. 리터럴에 붙이지 않으면 접속 콜레이션과 섞여 UNION 이 1271 로 죽는다. */
    private static final String COLLATION = " COLLATE utf8mb4_unicode_ci";
    private static final String NULL_TEXT = "CAST(NULL AS CHAR)" + COLLATION;

    private final JdbcTemplate jdbc;
    private final com.ruleup.ruleup_backend.challenge.view.ChallengeMasking masking;

    @Transactional(readOnly = true)
    public ChallengeListResponse myChallenges(UUID userId, String filterRaw, String cursorRaw, Integer sizeRaw) {
        MyChallengeFilter filter = MyChallengeFilter.parse(filterRaw);
        Cursor cursor = decode(cursorRaw);
        int size = sizeRaw == null ? DEFAULT_SIZE : Math.max(1, Math.min(sizeRaw, MAX_SIZE));

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM (")
                .append(liveSelect(filter, userId, args));
        if (filter != MyChallengeFilter.IN_PROGRESS) {
            // 진행 중 방은 삭제 대상이 아니므로 이력을 볼 이유가 없다.
            sql.append(" UNION ALL ").append(historySelect(filter, userId, args));
        }
        sql.append(") t ");
        if (cursor != null) {
            sql.append("WHERE (COALESCE(t.end_date, '9999-12-31') < ? OR (COALESCE(t.end_date, '9999-12-31') = ? AND t.challenge_id < ?)) ");
            args.add(java.sql.Date.valueOf(cursor.endDate()));
            args.add(java.sql.Date.valueOf(cursor.endDate()));
            args.add(toBytes(cursor.challengeId()));
        }
        sql.append("ORDER BY COALESCE(t.end_date, '9999-12-31') DESC, t.challenge_id DESC LIMIT ?");
        args.add(size + 1);

        List<Row> fetched = jdbc.query(sql.toString(), (rs, i) -> mapRow(rs), args.toArray());
        boolean hasNext = fetched.size() > size;
        List<Row> page = hasNext ? fetched.subList(0, size) : fetched;

        // 목록이라 차단 집합을 한 번만 읽는다.
        Set<UUID> masked = masking.maskedFor(userId);
        List<ChallengeListResponse.Item> items = page.stream()
                .map(r -> toItem(r, filter, masked)).toList();
        String next = hasNext && !page.isEmpty()
                ? encode(new Cursor(page.get(page.size() - 1).endDate == null ? LocalDate.of(9999,12,31) : page.get(page.size() - 1).endDate, page.get(page.size() - 1).challengeId))
                : null;
        return new ChallengeListResponse(items, next, hasNext);
    }

    // ===== 원천 1: 살아 있는 방 =====
    private String liveSelect(MyChallengeFilter filter, UUID userId, List<Object> args) {
        args.add(toBytes(userId));
        String memberAndChallenge = switch (filter) {
            case IN_PROGRESS -> "m.status = 'ACTIVE' AND c.status IN ('UPCOMING','ACTIVE')";
            case COMPLETED -> "m.status = 'ACTIVE' AND c.status = 'COMPLETED'";
            case LEFT -> "m.status IN ('LEFT','REMOVED')";
        };
        return "SELECT c.id AS challenge_id, c.title, c.ai_title, c.moderation_title, " +
                "       c.description, c.moderation_description, c.image_url, c.moderation_image, " +
                "       c.category, c.mode, c.visibility, c.status, " +
                "       c.participant_count, c.capacity, c.min_tier, c.weekly_count, " +
                "       c.start_date, c.end_date, CASE WHEN c.owner_id=m.user_id THEN 'OWNER' ELSE 'MEMBER' END AS my_role, c.owner_type, " +
                "       COALESCE(m.leave_reason,m.left_type) AS left_type, m.left_at, " +
                // 성공률은 판정 대비다 — progress_rate(목표 대비 진척도)와 다른 값이므로 섞지 않는다.
                // 판정이 하나도 없으면 NULL: 비율을 만들 수 없는 상태를 0 으로 채우면
                // 아직 아무것도 하지 않은 사용자에게 「성공률 0%」를 그리게 된다.
                "       CAST(CASE WHEN (m.success_days + m.fail_days) = 0 THEN NULL " +
                "                 ELSE m.success_days / (m.success_days + m.fail_days) END " +
                "            AS DECIMAL(6,4)) AS success_rate " +
                "FROM challenge_members m JOIN challenges c ON c.id = m.challenge_id " +
                "WHERE m.user_id = ? AND c.deleted_at IS NULL AND " + memberAndChallenge;
    }

    // ===== 원천 2: 삭제된 방의 이력 스냅샷 =====
    private String historySelect(MyChallengeFilter filter, UUID userId, List<Object> args) {
        args.add(toBytes(userId));
        // 삭제 시점에 ACTIVE 였으면 완료 탭, 그 전에 나갔으면 이탈 탭이다.
        String leftTypeCondition = filter == MyChallengeFilter.COMPLETED
                ? "h.left_type = 'ACTIVE_AT_DELETE'"
                : "h.left_type IN ('LEFT','REMOVED')";
        // 스냅샷에 없는 값은 CAST 로 타입을 못박고 테이블 콜레이션을 붙인다 — 맨 NULL 은 UNION 컬럼 타입이
        // 드라이버마다 갈리고, 문자열 리터럴은 접속 콜레이션을 따라와 컬럼과 섞이면 UNION 이 거절된다.
        return "SELECT h.challenge_id, ch.title_snapshot AS title, ch.ai_title_snapshot AS ai_title, " +
                "       " + text("APPROVED") + " AS moderation_title, ch.description_snapshot AS description, " +
                "       " + text("APPROVED") + " AS moderation_description, " +
                "       ch.image_snapshot AS image_url, " + text("APPROVED") + " AS moderation_image, " +
                "       ch.category, ch.mode, ch.visibility, " +
                "       " + text("COMPLETED") + " AS status, " +
                "       ch.final_member_count AS participant_count, ch.capacity, ch.min_tier, ch.weekly_count, " +
                "       ch.start_date, ch.end_date, h.final_role AS my_role, " +
                "       ch.owner_type_snapshot AS owner_type, COALESCE(h.leave_reason,h.left_type) AS left_type, h.left_at, " +
                // 이력에는 퍼센트(0~100)로 적재된다 — 계약은 0~1 이라 여기서 되돌린다.
                "       CAST(h.final_success_rate / 100 AS DECIMAL(6,4)) AS success_rate " +
                "FROM challenge_member_history h " +
                "JOIN challenge_history ch ON ch.challenge_id = h.challenge_id " +
                "WHERE h.user_id = ? AND NOT EXISTS (SELECT 1 FROM challenges c WHERE c.id=h.challenge_id) AND " + leftTypeCondition;
    }

    private static String text(String literal) {
        return "_utf8mb4'" + literal + "'" + COLLATION;
    }

    private ChallengeListResponse.Item toItem(Row r, MyChallengeFilter filter, Set<UUID> masked) {
        boolean leftTab = filter == MyChallengeFilter.LEFT;
        // 신고해 차단한 방은 심사 가려짐과 같은 자리로 내린다 — 표시 규칙은 ChallengeView 와 하나다.
        boolean hidden = masked.contains(r.challengeId);
        return new ChallengeListResponse.Item(
                r.challengeId.toString(),
                // 심사 중·거부면 AI 임시 제목 / 빈 설명 / 기본 이미지로 대체 표시한다.
                hidden ? com.ruleup.ruleup_backend.challenge.view.ChallengeView.REPORTED_TITLE
                        : !publicVisible(r.moderationTitle) ? r.aiTitle : r.title,
                (hidden || !publicVisible(r.moderationDescription)) ? null : r.description,
                (hidden || !publicVisible(r.moderationImage)) ? null : r.imageUrl,
                r.category,
                r.mode,
                r.visibility,
                r.status,
                r.participantCount,
                r.capacity,
                r.minTier,
                r.weeklyCount,
                r.startDate.toString(),
                r.endDate == null ? null : r.endDate.toString(),
                myRole(r.myRole),
                r.ownerType,
                r.successRate,
                leftTab ? leftType(r.leftType) : null,
                leftTab && r.leftAt != null ? r.leftAt : null);
    }

    /** 공동 관리자(MANAGER)는 폐기됐다 — 이력에 남은 값은 일반 멤버로 읽는다. */
    private String myRole(String raw) {
        return "OWNER".equals(raw) ? "OWNER" : "MEMBER";
    }

    /**
     * 저장 값을 API 계약 enum 으로 옮긴다.
     *
     * <p><b>레거시</b> — 클래스 주석 ② 참고. 저장 컬럼이 {@code enum('LEAVE','KICK')} 이라 계약의 7종을
     * 두 값으로만 내린다. 자동 강퇴 배치가 없어 지금은 손실이 0 이지만, 배치를 붙일 때 이 매핑이 아니라
     * 저장 컬럼부터 확장해야 한다.
     */
    private String leftType(String raw) {
        if (raw == null) return null;
        return switch (raw) {
            case LIVE_LEAVE, "LEFT", "VOLUNTARY" -> "SELF";
            case LIVE_KICK, "REMOVED", "KICKED" -> "KICK_BY_OWNER";
            case "SANCTION" -> "AUTO_LOCK";
            case "TIER_GATE" -> "AUTO_TIER";
            case "DORMANT", "ADMIN_CLOSE" -> raw;
            default -> null;                      // ACTIVE_AT_DELETE — 나간 적이 없다
        };
    }

    private boolean publicVisible(String moderationStatus) {
        return moderationStatus == null
                || TargetModerationStatus.valueOf(moderationStatus).isPubliclyVisible();
    }

    // ===== 커서 =====
    private record Cursor(LocalDate endDate, UUID challengeId) {}

    private Cursor decode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int split = decoded.lastIndexOf('|');
            if (split <= 0) throw new IllegalArgumentException();
            return new Cursor(LocalDate.parse(decoded.substring(0, split)),
                    UUID.fromString(decoded.substring(split + 1)));
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.CURSOR_INVALID);
        }
    }

    private String encode(Cursor cursor) {
        String raw = cursor.endDate() + "|" + cursor.challengeId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // ===== 매핑 =====
    private record Row(UUID challengeId, String title, String aiTitle, String moderationTitle,
                       String description, String moderationDescription, String imageUrl,
                       String moderationImage, String category, String mode, String visibility,
                       String status, Integer participantCount, Integer capacity, String minTier,
                       Integer weeklyCount, LocalDate startDate, LocalDate endDate, String myRole,
                       String ownerType, String leftType, String leftAt, Double successRate) {}

    private Row mapRow(ResultSet rs) throws SQLException {
        java.sql.Timestamp leftAt = rs.getTimestamp("left_at");
        return new Row(
                toUuid(rs.getBytes("challenge_id")),
                rs.getString("title"), rs.getString("ai_title"), rs.getString("moderation_title"),
                rs.getString("description"), rs.getString("moderation_description"),
                rs.getString("image_url"), rs.getString("moderation_image"),
                rs.getString("category"), rs.getString("mode"), rs.getString("visibility"),
                rs.getString("status"),
                intOrNull(rs, "participant_count"),
                intOrNull(rs, "capacity"),
                rs.getString("min_tier"),
                intOrNull(rs, "weekly_count"),
                rs.getDate("start_date").toLocalDate(), rs.getDate("end_date") == null ? null : rs.getDate("end_date").toLocalDate(),
                rs.getString("my_role"), rs.getString("owner_type"), rs.getString("left_type"),
                leftAt == null ? null : leftAt.toInstant().toString(),
                successRate(rs));
    }

    /** 0~1 성공률. 소수 셋째 자리까지 — 화면이 「88%」로 그리므로 그 이상의 정밀도는 의미가 없다. */
    private static Double successRate(ResultSet rs) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal("success_rate");
        if (value == null) return null;
        return Math.round(value.doubleValue() * 1000.0) / 1000.0;
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
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
