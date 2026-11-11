package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.domain.AdminAuditLog;
import com.ruleup.ruleup_backend.admin.dto.AdminUserMessageDtos;
import com.ruleup.ruleup_backend.common.DbTime;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationWindow;
import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 운영자 → 회원 개별 알림(2026-11-11).
 *
 * <h4>왜 있나</h4>
 * 운영자가 회원에게 말을 걸 통로가 CS 답변뿐이었다. 그래서 이의 사유 칸에 문의를 적은 사람처럼
 * <b>문의를 남기지 않은 사람</b>에게는 답할 방법이 없었다. 공지는 전원에게 가므로 대신 쓸 수 없다.
 *
 * <h4>검색 순서</h4>
 * 최근 30일에 이의·신고·문의를 한 사람이 위에 온다. 연락할 일이 생기는 사람이 대개 그들이고,
 * 닉네임이 겹쳐도 「방금 이의를 낸 그 사람」을 바로 고를 수 있다. 검색어가 없으면 그 사람들만 보여준다.
 *
 * <h4>앱 수정이 필요 없다</h4>
 * {@link NotificationType#OPERATOR_MESSAGE} 는 앱이 모르는 타입이지만, 앱은 모르는 타입도 알림함에 제목·본문으로
 * 세우고 푸시도 그대로 띄운다. 딥링크가 없어 탭하면 알림함에 머문다.
 *
 * <p>관리자 DB 계정이 이미 가진 권한(users·verification_appeals·reports·inquiries 읽기, notifications 쓰기)만 쓴다.
 */
@Service
@RequiredArgsConstructor
public class AdminUserMessageService {

    static final int RECENT_DAYS = 30;
    private static final int DEFAULT_SIZE = 30;
    private static final int MAX_SIZE = 100;
    static final int TITLE_MAX = 50;
    static final int BODY_MAX = 500;

    private static final String EPOCH = "TIMESTAMP '1970-01-01 00:00:00'";

    private final JdbcTemplate jdbc;
    private final AdminAuditService auditService;
    private final NotificationPublisher notificationPublisher;

    // ===== 검색 =====

    @Transactional(readOnly = true)
    public AdminUserMessageDtos.SearchResponse search(UUID operatorId, String q, Integer size) {
        auditService.allowed(operatorId, AdminAction.USER_SEARCH, null, null, q);

        int limit = (size == null || size <= 0) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        String keyword = (q == null) ? "" : q.strip();
        Object since = DbTime.utc(Instant.now().minus(Duration.ofDays(RECENT_DAYS)));

        List<Object> args = new ArrayList<>(List.of(since, since, since));
        StringBuilder where = new StringBuilder(" WHERE u.role = 'MEMBER' AND u.status <> 'WITHDRAWN'");
        if (keyword.isEmpty()) {
            where.append(" AND (a.uid IS NOT NULL OR r.uid IS NOT NULL OR i.uid IS NOT NULL)");
        } else {
            UUID asId = parseId(keyword);
            if (asId != null) {
                where.append(" AND u.id = ?");
                args.add(bytes(asId));
            } else {
                // 승인 닉네임과 심사 중인 새 닉네임을 둘 다 본다 — 회원이 방금 바꾼 이름으로 물어올 수 있다.
                String like = "%" + escapeLike(keyword.toLowerCase()) + "%";
                where.append(" AND (LOWER(u.approved_nickname) LIKE ? OR LOWER(u.nickname) LIKE ?)");
                args.add(like);
                args.add(like);
            }
        }
        args.add(limit);

        String sql = "SELECT u.id, u.approved_nickname, u.status, u.created_at,"
                + " COALESCE(a.cnt, 0) AS appeals, COALESCE(r.cnt, 0) AS reports,"
                + " COALESCE(i.cnt, 0) AS inquiries, COALESCE(i.open_cnt, 0) AS open_inquiries,"
                + " a.last_at AS appeal_at, r.last_at AS report_at, i.last_at AS inquiry_at"
                + " FROM users u"
                + " LEFT JOIN (SELECT userId AS uid, COUNT(*) AS cnt, MAX(acceptedAt) AS last_at"
                + "   FROM verification_appeals WHERE acceptedAt >= ? GROUP BY userId) a ON a.uid = u.id"
                + " LEFT JOIN (SELECT reporter_id AS uid, COUNT(*) AS cnt, MAX(created_at) AS last_at"
                + "   FROM reports WHERE created_at >= ? GROUP BY reporter_id) r ON r.uid = u.id"
                + " LEFT JOIN (SELECT user_id AS uid, COUNT(*) AS cnt, SUM(status = 'RECEIVED') AS open_cnt,"
                + "   MAX(created_at) AS last_at FROM inquiries WHERE created_at >= ? GROUP BY user_id) i ON i.uid = u.id"
                + where
                + " ORDER BY GREATEST(COALESCE(a.last_at, " + EPOCH + "), COALESCE(r.last_at, " + EPOCH + "),"
                + " COALESCE(i.last_at, " + EPOCH + ")) DESC, u.created_at DESC, u.id DESC"
                + " LIMIT ?";

        List<AdminUserMessageDtos.UserItem> items = jdbc.query(sql, (rs, n) -> item(rs), args.toArray());
        return new AdminUserMessageDtos.SearchResponse(items, RECENT_DAYS);
    }

    private static AdminUserMessageDtos.UserItem item(ResultSet rs) throws SQLException {
        Instant appealAt = DbTime.read(rs, "appeal_at");
        Instant reportAt = DbTime.read(rs, "report_at");
        Instant inquiryAt = DbTime.read(rs, "inquiry_at");

        Instant last = null;
        String lastType = null;
        if (appealAt != null) { last = appealAt; lastType = "APPEAL"; }
        if (reportAt != null && (last == null || reportAt.isAfter(last))) { last = reportAt; lastType = "REPORT"; }
        if (inquiryAt != null && (last == null || inquiryAt.isAfter(last))) { last = inquiryAt; lastType = "INQUIRY"; }

        Instant joinedAt = DbTime.read(rs, "created_at");
        return new AdminUserMessageDtos.UserItem(
                uuid(rs.getBytes("id")).toString(),
                rs.getString("approved_nickname"),
                rs.getString("status"),
                joinedAt == null ? null : joinedAt.toString(),
                rs.getInt("appeals"),
                rs.getInt("reports"),
                rs.getInt("inquiries"),
                rs.getInt("open_inquiries"),
                last == null ? null : last.toString(),
                lastType);
    }

    // ===== 보내기 =====

    /**
     * 회원 한 명에게 알림을 적재하고 푸시를 큐에 넣는다. 감사 로그에는 제목·본문의 다이제스트만 남는다.
     *
     * <p>2단계 확인(428)은 두지 않는다 — 받는 사람이 한 명이고 같은 화면에서 보낸 내용을 바로 확인할 수 있다.
     * 전원 팬아웃인 공지와 다르다.
     */
    @Transactional
    public AdminUserMessageDtos.MessageResponse send(UUID operatorId, UUID targetUserId,
                                                     AdminUserMessageDtos.MessageRequest request) {
        String title = (request == null || request.title() == null) ? "" : request.title().strip();
        String body = (request == null || request.body() == null) ? "" : request.body().strip();
        if (title.isEmpty() || body.isEmpty() || title.length() > TITLE_MAX || body.length() > BODY_MAX)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        List<String> nickname = jdbc.query("SELECT approved_nickname FROM users"
                        + " WHERE id = ? AND role = 'MEMBER' AND status <> 'WITHDRAWN'",
                (rs, n) -> rs.getString(1), bytes(targetUserId));
        if (nickname.isEmpty()) throw new BusinessException(ErrorCode.USER_NOT_FOUND);

        auditService.allowed(operatorId, AdminAction.USER_MESSAGE, AdminAuditLog.TargetType.USER,
                targetUserId, title + "\n" + body);

        Instant now = Instant.now();
        Notification stored = notificationPublisher.publish(NotificationEvent.authored(targetUserId,
                        NotificationType.OPERATOR_MESSAGE, title, body,
                        Map.of(NotificationParams.MESSAGE_ID, UUID.randomUUID().toString())))
                // 멱등 키가 매번 새 값이라 겹칠 일이 없다. 비었다면 적재가 무언가에 막힌 것이다.
                .orElseThrow(() -> new IllegalStateException("운영자 메시지 적재 실패 user=" + targetUserId));

        return new AdminUserMessageDtos.MessageResponse(stored.getId().toString(),
                targetUserId.toString(), nickname.getFirst(), now.toString(), NotificationWindow.isNight(now));
    }

    // ===== 도우미 =====

    /** LIKE 의 와일드카드를 글자 그대로 찾게 한다 — 닉네임에 % · _ 가 들어갈 수 있다. */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static UUID parseId(String raw) {
        if (raw.length() != 36) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
