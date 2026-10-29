package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.domain.AdminAuditLog;
import com.ruleup.ruleup_backend.admin.dto.AdminAppealDtos;
import com.ruleup.ruleup_backend.common.DbTime;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 이의 내역(운영자) — Backend #361.
 *
 * <h4>판단하는 화면이 아니다</h4>
 * 이의는 접수하는 순간 인용되고 횟수 한도도 없다(인증 정책 §5). 그래서 상태 필터도, 처리 버튼도 없다.
 * 운영자는 남용 의심을 확인하거나 「제 이의가 반영됐나요」 문의에 답하려고 이 내역을 본다.
 *
 * <h4>원래 실패 사유는 이의 행에서 읽는다</h4>
 * 인용하면 판정 행의 사유가 지워지고, 확정 전 인용이라 {@code verification_failure_details} 도 생기지 않는다.
 * 접수 때 이의 행에 옮겨 둔 설명(V21)이 유일한 원천이다 — 그 전 접수는 비어 있다.
 *
 * <p>관리자 DB 계정이 읽는 테이블을 늘리지 않으려고 이의·회원·방 세 테이블만 읽는다.
 */
@Service
@RequiredArgsConstructor
public class AdminAppealService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int PAGE = 30;
    private static final int PREVIEW_LENGTH = 80;
    private static final Duration RECENT = Duration.ofDays(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final AdminAuditService auditService;
    private final AdminImageLinks imageLinks;

    private static final String FROM = " FROM verification_appeals a"
            + " LEFT JOIN users u ON u.id = a.userId"
            + " LEFT JOIN challenges c ON c.id = a.challengeId";

    // ===== 목록 =====

    /**
     * 최신순 목록. 기간은 접수일(KST) 양끝 포함이다.
     *
     * @param hasImage true 면 사진이 붙은 것만, false 면 없는 것만, null 이면 전부
     */
    @Transactional(readOnly = true)
    public AdminAppealDtos.QueueResponse queue(UUID operatorId, String from, String to, String userIdRaw,
                                               String challengeIdRaw, Boolean hasImage, String cursor, Integer size) {
        auditService.allowed(operatorId, AdminAction.APPEAL_QUEUE_VIEW, null, null, null);

        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        UUID userId = parseId(userIdRaw);
        UUID challengeId = parseId(challengeIdRaw);
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        int limit = (size == null || size <= 0 || size > 100) ? PAGE : size;

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (fromDate != null) {
            where.append(" AND a.acceptedAt >= ?");
            args.add(DbTime.utc(fromDate.atStartOfDay(KST).toInstant()));
        }
        if (toDate != null) {
            where.append(" AND a.acceptedAt < ?");
            args.add(DbTime.utc(toDate.plusDays(1).atStartOfDay(KST).toInstant()));
        }
        if (userId != null) {
            where.append(" AND a.userId = ?");
            args.add(bytes(userId));
        }
        if (challengeId != null) {
            where.append(" AND a.challengeId = ?");
            args.add(bytes(challengeId));
        }
        if (hasImage != null) {
            where.append(hasImage ? " AND a.imageUrl IS NOT NULL AND a.imageUrl <> ''"
                    : " AND (a.imageUrl IS NULL OR a.imageUrl = '')");
        }

        long total = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        StringBuilder pageWhere = new StringBuilder(where);
        AdminCursors.TimeIdCursor after = AdminCursors.toTimeId(cursor);
        if (after != null) {
            // 같은 시각에 여러 건이 있어도 건너뛰지 않게 (시각, id) 로 이어받는다.
            pageWhere.append(" AND (a.acceptedAt < ? OR (a.acceptedAt = ? AND a.id < ?))");
            pageArgs.add(DbTime.utc(after.at()));
            pageArgs.add(DbTime.utc(after.at()));
            pageArgs.add(bytes(after.id()));
        }
        pageArgs.add(limit + 1);

        // 다음 커서를 만들려면 마지막 행 뒤에 뭔가 더 있는지 알아야 한다 — 한 건 더 읽는다.
        List<Row> rows = jdbc.query("SELECT a.id, a.acceptedAt, a.userId, u.approved_nickname, a.challengeId,"
                        + " c.title, c.category, a.targetDate, a.failureReason, a.reason, a.imageUrl"
                        + FROM + pageWhere + " ORDER BY a.acceptedAt DESC, a.id DESC LIMIT ?",
                (rs, i) -> Row.of(rs), pageArgs.toArray());

        boolean hasMore = rows.size() > limit;
        List<Row> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? AdminCursors.ofTimeId(page.getLast().acceptedAt(), page.getLast().id()) : null;

        return new AdminAppealDtos.QueueResponse(
                page.stream().map(r -> new AdminAppealDtos.Item(
                        r.id().toString(), r.acceptedAt().toString(), r.userId().toString(), r.nickname(),
                        r.challengeId().toString(), r.title(), r.category(), r.targetDate().toString(),
                        r.failureReason(), preview(r.reason()), hasText(r.imageUrl())))
                        .toList(),
                nextCursor, total);
    }

    // ===== 상세 =====

    /** 상세 — 사유 원문과 증빙 사진이 담긴 <b>개인정보 열람</b>이라 목록과 다른 action 으로 남긴다. */
    @Transactional(readOnly = true)
    public AdminAppealDtos.Detail detail(UUID operatorId, UUID appealId) {
        auditService.allowed(operatorId, AdminAction.APPEAL_VIEW,
                AdminAuditLog.TargetType.APPEAL, appealId, null);

        List<AdminAppealDtos.Detail> found = jdbc.query("SELECT a.id, a.acceptedAt, a.userId, u.approved_nickname,"
                        + " u.status, a.challengeId, c.title, c.category, a.targetDate, a.verificationDailyId, a.reason,"
                        + " a.imageUrl, a.failureReason, a.failureSummary, a.failureExpected, a.failureActual"
                        + FROM + " WHERE a.id = ?",
                (rs, i) -> detailOf(rs), bytes(appealId));
        if (found.isEmpty()) throw new BusinessException(ErrorCode.APPEAL_NOT_FOUND);
        return found.getFirst();
    }

    private AdminAppealDtos.Detail detailOf(ResultSet rs) throws SQLException {
        UUID userId = uuid(rs.getBytes("userId"));
        String imageUrl = rs.getString("imageUrl");
        AdminImageLinks.Links links = imageLinks.presign(hasText(imageUrl) ? List.of(imageUrl) : List.of());
        String reasonCode = rs.getString("failureReason");
        String summary = rs.getString("failureSummary");
        AdminAppealDtos.Failure failure = (reasonCode == null && summary == null) ? null
                : new AdminAppealDtos.Failure(reasonCode, summary,
                json(rs.getString("failureExpected")), json(rs.getString("failureActual")));

        return new AdminAppealDtos.Detail(
                uuid(rs.getBytes("id")).toString(),
                DbTime.read(rs, "acceptedAt").toString(),
                userId.toString(),
                rs.getString("approved_nickname"),
                rs.getString("status"),
                uuid(rs.getBytes("challengeId")).toString(),
                rs.getString("title"),
                rs.getString("category"),
                rs.getObject("targetDate", LocalDate.class).toString(),
                uuid(rs.getBytes("verificationDailyId")).toString(),
                rs.getString("reason"),
                links.urls(),
                links.expiresAt(),
                failure,
                recentCount(userId));
    }

    /** 같은 회원의 최근 30일 이의 수 — 남용 의심을 볼 때 이 건 하나가 아니라 빈도를 함께 본다. */
    private long recentCount(UUID userId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM verification_appeals WHERE userId = ? AND acceptedAt >= ?",
                Long.class, bytes(userId), DbTime.utc(Instant.now().minus(RECENT)));
        return n == null ? 0 : n;
    }

    // ===== 내부 =====

    private record Row(UUID id, Instant acceptedAt, UUID userId, String nickname, UUID challengeId,
                       String title, String category, LocalDate targetDate, String failureReason,
                       String reason, String imageUrl) {

        static Row of(ResultSet rs) throws SQLException {
            return new Row(uuid(rs.getBytes("id")), DbTime.read(rs, "acceptedAt"), uuid(rs.getBytes("userId")),
                    rs.getString("approved_nickname"), uuid(rs.getBytes("challengeId")), rs.getString("title"),
                    rs.getString("category"), rs.getObject("targetDate", LocalDate.class),
                    rs.getString("failureReason"), rs.getString("reason"), rs.getString("imageUrl"));
        }
    }

    private Map<String, Object> json(String raw) {
        if (raw == null) return null;
        try {
            return JSON.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return null;                                    // 설명 보조값이라 깨져도 상세를 막지 않는다
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    /** 잘못된 id 를 500 으로 흘리지 않는다 — 잘못된 입력이다. */
    private static UUID parseId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private static String preview(String reason) {
        return reason.length() <= PREVIEW_LENGTH ? reason : reason.substring(0, PREVIEW_LENGTH) + "…";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static byte[] bytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }

    private static UUID uuid(byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
