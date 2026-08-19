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
import java.util.UUID;

/**
 * 내 챌린지 목록 조회 (GET /api/v1/challenges).
 *
 * <p>원천이 둘이라 UNION 으로 읽는다. 살아 있는 방은 {@code challenges}, <b>완료 후 하드 삭제된 방</b>은
 * 삭제 배치가 직전에 적재한 이력({@code challenge_history} · {@code challenge_member_history})이다.
 * 완료 기록은 방이 사라진 뒤에도 마이페이지에서 열람할 수 있어야 하는데(마이페이지 §2-1),
 * 삭제 배치가 방 데이터를 통째로 지우므로 이력을 함께 읽지 않으면 완료 탭이 시간이 지날수록 비어 버린다.
 *
 * <p>정렬·커서 키는 두 원천이 모두 가진 <b>(종료일, 챌린지 id)</b> 내림차순이다. 이력에는 가입 시각도
 * 이탈 시각도 온전히 남지 않아 다른 키로는 두 원천을 한 줄로 세울 수 없다.
 *
 * <h2>⚠️ 알려진 제약 두 가지 — 레거시로 남긴다</h2>
 *
 * <p><b>① 이력에서 읽은 항목은 필드 절반이 null 이다.</b> {@code challenge_history} 가 보존하는 값은
 * 제목·이미지·카테고리·기간뿐이라 설명·모드·공개범위·인원·정원·최소티어·주간횟수·방장유형을 채울 수 없다.
 * 0 이나 기본값으로 메우면 완료 카드가 "정원 0명짜리 솔로 방"처럼 거짓을 그리므로 null 로 둔다.
 *
 * <p>이건 드문 경우가 아니다. 삭제 배치가 매일 04:10 에 {@code status='COMPLETED'} 를 전부 지우므로
 * 완료된 방이 살아 있는 시간은 길어야 하루다 — 즉 <b>완료 탭은 사실상 전부 이력에서 읽히고,
 * 위 필드들은 항상 null 이라고 봐야 한다</b>. 그럼에도 지금 막지 않는 전제는 하나다:
 * 완료 카드가 제목·이미지·기간·최종 랭킹만 그린다는 것. <b>이 전제가 깨지면 그때가 교체 시점이다.</b>
 * 스냅샷 컬럼을 늘리는 마이그레이션이 먼저이며, 적재가 삭제 직전 1회뿐이라
 * <b>그 이전 삭제분은 소급 복구되지 않는다</b>.
 *
 * <p><b>② {@code leftType} 이 두 값으로 뭉개진다.</b> {@code challenge_members.left_type} 이
 * {@code enum('LEAVE','KICK')} 이라 API 계약의 7종을 {@code SELF} · {@code KICK_BY_OWNER} 로만 내린다.
 * 지금 손실이 없는 이유는 나머지 5종을 남길 자동 강퇴 배치(신고 누적·연속 실패·권한 미허용·티어 미달·
 * 계정 잠금)가 <b>아직 존재하지 않아 실제 발생이 0건</b>이기 때문이다 — 값이 뭉개진 이력이 쌓이는
 * 상황이 아니다. <b>교체 시점</b>: 자동 강퇴 배치를 붙이는 순간. 그때는 API 매핑이 아니라
 * <b>저장 컬럼(enum 확장 마이그레이션)부터</b> 갈라야 한다. 배치를 먼저 붙이고 매핑을 나중에 고치면
 * 그사이 강퇴분은 원인을 영영 알 수 없다.
 */
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
            sql.append("WHERE (t.end_date < ? OR (t.end_date = ? AND t.challenge_id < ?)) ");
            args.add(java.sql.Date.valueOf(cursor.endDate()));
            args.add(java.sql.Date.valueOf(cursor.endDate()));
            args.add(toBytes(cursor.challengeId()));
        }
        sql.append("ORDER BY t.end_date DESC, t.challenge_id DESC LIMIT ?");
        args.add(size + 1);

        List<Row> fetched = jdbc.query(sql.toString(), (rs, i) -> mapRow(rs), args.toArray());
        boolean hasNext = fetched.size() > size;
        List<Row> page = hasNext ? fetched.subList(0, size) : fetched;

        List<ChallengeListResponse.Item> items = page.stream()
                .map(r -> toItem(r, filter)).toList();
        String next = hasNext && !page.isEmpty()
                ? encode(new Cursor(page.get(page.size() - 1).endDate, page.get(page.size() - 1).challengeId))
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
                "       c.start_date, c.end_date, m.role AS my_role, c.owner_type, " +
                "       m.left_type, m.left_at " +
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
        return "SELECT h.challenge_id, ch.title_snapshot AS title, " + NULL_TEXT + " AS ai_title, " +
                "       " + text("APPROVED") + " AS moderation_title, " + NULL_TEXT + " AS description, " +
                "       " + text("APPROVED") + " AS moderation_description, " +
                "       ch.image_snapshot AS image_url, " + text("APPROVED") + " AS moderation_image, " +
                "       ch.category, " + NULL_TEXT + " AS mode, " + NULL_TEXT + " AS visibility, " +
                "       " + text("COMPLETED") + " AS status, " +
                "       CAST(NULL AS SIGNED) AS participant_count, CAST(NULL AS SIGNED) AS capacity, " +
                "       " + NULL_TEXT + " AS min_tier, CAST(NULL AS SIGNED) AS weekly_count, " +
                "       ch.start_date, ch.end_date, h.final_role AS my_role, " +
                "       " + NULL_TEXT + " AS owner_type, h.left_type, h.left_at " +
                "FROM challenge_member_history h " +
                "JOIN challenge_history ch ON ch.challenge_id = h.challenge_id " +
                "WHERE h.user_id = ? AND " + leftTypeCondition;
    }

    private static String text(String literal) {
        return "_utf8mb4'" + literal + "'" + COLLATION;
    }

    private ChallengeListResponse.Item toItem(Row r, MyChallengeFilter filter) {
        boolean leftTab = filter == MyChallengeFilter.LEFT;
        return new ChallengeListResponse.Item(
                r.challengeId.toString(),
                // 심사 중·거부면 AI 임시 제목 / 빈 설명 / 기본 이미지로 대체 표시한다.
                publicVisible(r.moderationTitle) ? r.title : r.aiTitle,
                publicVisible(r.moderationDescription) ? r.description : null,
                publicVisible(r.moderationImage) ? r.imageUrl : null,
                r.category,
                r.mode,
                r.visibility,
                r.status,
                r.participantCount,
                r.capacity,
                r.minTier,
                r.weeklyCount,
                r.startDate.toString(),
                r.endDate.toString(),
                myRole(r.myRole),
                r.ownerType,
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
            case LIVE_LEAVE, "LEFT" -> "SELF";
            case LIVE_KICK, "REMOVED" -> "KICK_BY_OWNER";
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
                       String ownerType, String leftType, String leftAt) {}

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
                rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate(),
                rs.getString("my_role"), rs.getString("owner_type"), rs.getString("left_type"),
                leftAt == null ? null : leftAt.toInstant().toString());
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
