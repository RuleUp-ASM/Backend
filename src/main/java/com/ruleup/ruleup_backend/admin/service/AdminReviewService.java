package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.domain.AdminAuditLog;
import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 신고 검토 — 백오피스 백엔드 4-2 · 공통 5-2-1 A #1~#3.
 *
 * <p><b>적재된 것을 읽고 상태만 종결한다.</b> 신고 접수와 스냅샷은 방 내부 모듈 소유이며
 * <b>스냅샷은 수정하지 않는다</b> — 원본이 바뀌어도 접수 시점의 값으로 판단해야 한다.
 *
 * <h4>신고자 신원은 어떤 응답에도 없다</h4>
 * 조회 SQL 에서 아예 뽑지 않는 편이 실수를 구조적으로 막는다. 접수자 남용은 불리언 하나
 * ({@code reporterFlagged})로만 나가고 상세는 이상탐지가 담당한다.
 *
 * <h4>결정은 둘뿐이다</h4>
 * {@code NO_ACTION} 과 {@code MODERATION_REJECT}. 제재와 직권 폐쇄는 각자의 엔드포인트가
 * 근거 신고를 함께 종결시키므로 여기서 낼 결정이 아니다 — 「신고를 제재로 종결」이라는 결정을
 * 여기 두면 <b>제재 없이 제재 종결된 신고</b>가 만들어진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminReviewService {

    private static final int PAGE = 30;

    /** 스냅샷은 값만 담긴 평평한 JSON 이라 설정 없는 기본 매퍼로 충분하다. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final ChallengeRepository challengeRepository;
    private final AdminAuditService auditService;
    private final AdminImageLinks imageLinks;
    private final NotificationPublisher notificationPublisher;

    // ===== 건별 큐 =====

    /**
     * 검토 큐(건별). <b>정렬은 접수 순</b>이고 처리 기한 필드를 두지 않는다 — 기한을 두면
     * 오래된 건이 심각도와 무관하게 위로 올라온다.
     */
    @Transactional(readOnly = true)
    public AdminDtos.ReportQueueResponse queue(UUID operatorId, String status, String targetType,
                                               String reason, Boolean flagged, String keyword,
                                               String cursor, Integer size) {
        auditService.allowed(operatorId, AdminAction.REPORT_QUEUE_VIEW, null, null, null);

        int limit = (size == null || size <= 0 || size > 100) ? PAGE : size;
        Instant after = AdminCursors.toInstant(cursor);

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(where, args, status, targetType, reason, flagged, keyword);

        // 커서는 정렬 축(created_at)을 그대로 이어받는다.
        if (after != null) {
            where.append(" AND r.created_at > ?");
            args.add(Timestamp.from(after));
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit + 1);   // 다음 커서가 있는지 보려면 한 건 더 읽어야 한다

        List<AdminDtos.ReportItem> rows = jdbc.query(
                SELECT_ITEM + where + " ORDER BY r.created_at ASC LIMIT ?",
                (rs, i) -> new AdminDtos.ReportItem(
                        uuid(rs.getBytes("id")).toString(),
                        rs.getString("target_type"),
                        uuid(rs.getBytes("target_id")).toString(),
                        rs.getString("target_label"),
                        rs.getString("reason"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant().toString(),
                        rs.getTimestamp("resolved_at") == null
                                ? null : rs.getTimestamp("resolved_at").toInstant().toString(),
                        rs.getInt("target_report_count"),
                        rs.getBoolean("reporter_flagged")),
                pageArgs.toArray());

        boolean hasMore = rows.size() > limit;
        List<AdminDtos.ReportItem> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? AdminCursors.ofInstant(Instant.parse(page.getLast().createdAt())) : null;

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reports r" + where, Long.class, args.toArray());

        return new AdminDtos.ReportQueueResponse(page, nextCursor, total == null ? 0 : total);
    }

    // ===== 대상 단위 큐 =====

    /**
     * <b>대상 단위로 묶은</b> 큐. 같은 대상에 대한 신고를 하나씩 보면 판단이 느려지고 같은
     * 사안을 여러 번 판단하게 된다. 건별 목록과 페이징 단위가 달라 경로를 나눴다.
     */
    @Transactional(readOnly = true)
    public AdminDtos.ReportTargetsResponse targets(UUID operatorId) {
        auditService.allowed(operatorId, AdminAction.REPORT_QUEUE_VIEW, null, null, null);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.target_type, r.target_id,
                       COUNT(*) AS cnt, MIN(r.created_at) AS first_at, MAX(r.created_at) AS last_at,
                       GROUP_CONCAT(HEX(r.id)) AS ids,
                       GROUP_CONCAT(r.reason) AS reasons,
                       MAX(u.approved_nickname) AS nickname, MAX(c.title) AS title
                  FROM reports r
                  LEFT JOIN users u ON u.id = r.target_id AND r.target_type = 'USER'
                  LEFT JOIN challenges c ON c.id = r.target_id AND r.target_type = 'CHALLENGE'
                 WHERE r.status = 'PENDING'
                 GROUP BY r.target_type, r.target_id
                 ORDER BY first_at ASC
                """);

        List<AdminDtos.TargetGroup> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            byte[] targetId = (byte[]) row.get("target_id");
            if (targetId == null) continue;
            boolean user = "USER".equals(row.get("target_type"));

            items.add(new AdminDtos.TargetGroup(
                    (String) row.get("target_type"),
                    uuid(targetId).toString(),
                    (String) (user ? row.get("nickname") : row.get("title")),
                    ((Number) row.get("cnt")).intValue(),
                    String.valueOf(row.get("first_at")),
                    String.valueOf(row.get("last_at")),
                    reasonCounts((String) row.get("reasons")),
                    hexIds((String) row.get("ids"))));
        }
        return new AdminDtos.ReportTargetsResponse(items);
    }

    // ===== 상세 =====

    /**
     * 신고 상세. 스냅샷 열람은 <b>개인정보 열람</b>이라 별도 action 으로 남긴다 —
     * "누가 언제 누구의 신고 내용을 봤는지"만 따로 뽑아낼 수 있어야 한다.
     */
    @Transactional(readOnly = true)
    public AdminDtos.ReportDetail detail(UUID operatorId, UUID reportId) {
        auditService.allowed(operatorId, AdminAction.SNAPSHOT_VIEW,
                AdminAuditLog.TargetType.REPORT, reportId, null);

        // reporter_id 를 값으로 뽑지 않는다 — 신원은 어떤 경로로도 나가면 안 되고,
        // 남용 여부만 서브쿼리 결과 불리언으로 받는다.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.target_type, r.target_id, r.reason, r.status, r.created_at,
                       r.resolved_at, r.resolution_note, r.resolved_by, s.payload,
                       COALESCE(u.approved_nickname, c.title) AS target_label,
                       EXISTS(SELECT 1 FROM anomaly_signals a
                               WHERE a.target_user_id = r.reporter_id
                                 AND a.signal_type = 'REPORT_ABUSE') AS reporter_flagged
                  FROM reports r
                  LEFT JOIN report_snapshots s ON s.report_id = r.id
                  LEFT JOIN users u ON u.id = r.target_id AND r.target_type = 'USER'
                  LEFT JOIN challenges c ON c.id = r.target_id AND r.target_type = 'CHALLENGE'
                 WHERE r.id = ?
                """, bytes(reportId));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);

        Map<String, Object> row = rows.getFirst();
        byte[] targetId = (byte[]) row.get("target_id");
        Map<String, Object> snapshot =
                snapshotOf(row.get("payload") == null ? null : String.valueOf(row.get("payload")));
        AdminImageLinks.Links links = imageLinks.presign(imageUrlsIn(snapshot));

        List<AdminDtos.Sibling> siblings = (targetId == null) ? List.of() : jdbc.query("""
                SELECT id, reason, status, created_at FROM reports
                 WHERE target_type = ? AND target_id = ? AND id <> ?
                 ORDER BY created_at DESC LIMIT 50
                """,
                (rs, i) -> new AdminDtos.Sibling(uuid(rs.getBytes(1)).toString(), rs.getString(2),
                        rs.getString(3), rs.getTimestamp(4).toInstant().toString()),
                row.get("target_type"), targetId, bytes(reportId));

        String status = (String) row.get("status");
        AdminDtos.Resolution resolution = "PENDING".equals(status) ? null
                : new AdminDtos.Resolution(status,
                        String.valueOf(row.get("resolved_at")),
                        (String) row.get("resolution_note"),
                        row.get("resolved_by") == null
                                ? null : uuid((byte[]) row.get("resolved_by")).toString());

        return new AdminDtos.ReportDetail(
                reportId.toString(),
                (String) row.get("target_type"),
                targetId == null ? null : uuid(targetId).toString(),
                (String) row.get("target_label"),
                (String) row.get("reason"),
                status,
                String.valueOf(row.get("created_at")),
                Boolean.TRUE.equals(row.get("reporter_flagged"))
                        || Long.valueOf(1L).equals(row.get("reporter_flagged")),
                new AdminDtos.Snapshot(snapshot, links.urls(), links.expiresAt()),
                siblings,
                resolution);
    }

    // ===== 종결 =====

    /**
     * 검토 결과 확정. <b>종결해도 각 신고자의 개인 차단은 유지</b>된다 — 차단은 제재가 아니라
     * 개인 선택이므로 운영자가 되돌릴 대상이 아니다.
     *
     * <p>{@code cascadeToTarget} 이 true 여도 <b>함께 종결할 대상은 서버가 다시 계산한다</b>.
     * 클라이언트가 id 목록을 만들면 목록을 그린 뒤 접수된 건이 조용히 큐에 남는다.
     */
    @Transactional
    public AdminDtos.ResolveResponse resolve(UUID operatorId, UUID reportId,
                                             AdminDtos.ResolveRequest request) {
        String decision = (request == null) ? null : request.decision();
        String status = switch (decision == null ? "" : decision) {
            case "NO_ACTION" -> "RESOLVED_NO_ACTION";
            case "MODERATION_REJECT" -> "RESOLVED_MODERATION_REJECT";
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST);
        };

        Map<String, Object> report = jdbc.queryForList(
                "SELECT target_type, target_id, status FROM reports WHERE id = ?", bytes(reportId))
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        if (!"PENDING".equals(report.get("status")))
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_RESOLVED);

        String targetType = (String) report.get("target_type");
        UUID targetId = uuid((byte[]) report.get("target_id"));

        // 콘텐츠 거부는 챌린지에만 있는 행선이다 — 유저 대상 신고의 갈래는 종결 아니면 계정 제재다.
        if ("RESOLVED_MODERATION_REJECT".equals(status) && !"CHALLENGE".equals(targetType))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        Instant now = Instant.now();
        auditService.allowed(operatorId, AdminAction.REPORT_RESOLVE,
                AdminAuditLog.TargetType.REPORT, reportId, status + "|" + note(request));

        // 조건부 UPDATE 로 선착순을 가린다 — 두 운영자가 동시에 종결해도 하나만 성공한다.
        int changed = update(status, note(request), operatorId, now, "id = ?", bytes(reportId));
        if (changed == 0) throw new BusinessException(ErrorCode.REVIEW_ALREADY_RESOLVED);

        int resolved = changed;
        if (request != null && request.cascadeToTarget()) {
            resolved += update(status, note(request), operatorId, now,
                    "target_type = ? AND target_id = ? AND status = 'PENDING'",
                    targetType, bytes(targetId));
        }

        if ("RESOLVED_MODERATION_REJECT".equals(status)) rejectChallengeContent(targetId);

        return new AdminDtos.ResolveResponse(itemOf(reportId), resolved);
    }

    /** 제재·직권 폐쇄가 근거 신고를 함께 종결시킬 때 쓰는 통로. @return 실제로 종결된 건수 */
    int resolveAsSanctioned(UUID operatorId, List<String> reportIds, String note) {
        if (reportIds == null || reportIds.isEmpty()) return 0;

        Instant now = Instant.now();
        int resolved = 0;
        for (String raw : reportIds) {
            UUID reportId;
            try {
                reportId = UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            // 이미 종결된 건은 조용히 건너뛴다 — 집행은 이미 끝났고, 여기서 409 를 내면
            // 제재가 성공했는데 응답은 실패인 상태가 만들어진다.
            int changed = update("RESOLVED_SANCTIONED", note, operatorId, now,
                    "id = ? AND status = 'PENDING'", bytes(reportId));
            if (changed > 0) {
                resolved += changed;
                auditService.allowed(operatorId, AdminAction.REPORT_RESOLVE,
                        AdminAuditLog.TargetType.REPORT, reportId, "RESOLVED_SANCTIONED");
            }
        }
        return resolved;
    }

    // ===== 내부 =====

    private static final String SELECT_ITEM = """
            SELECT r.id, r.target_type, r.target_id, r.reason, r.status, r.created_at, r.resolved_at,
                   COALESCE(u.approved_nickname, c.title) AS target_label,
                   (SELECT COUNT(*) FROM reports r2
                     WHERE r2.target_type = r.target_type AND r2.target_id = r.target_id)
                       AS target_report_count,
                   EXISTS(SELECT 1 FROM anomaly_signals a
                           WHERE a.target_user_id = r.reporter_id
                             AND a.signal_type = 'REPORT_ABUSE') AS reporter_flagged
              FROM reports r
              LEFT JOIN users u ON u.id = r.target_id AND r.target_type = 'USER'
              LEFT JOIN challenges c ON c.id = r.target_id AND r.target_type = 'CHALLENGE'
            """;

    private void appendFilters(StringBuilder where, List<Object> args, String status,
                               String targetType, String reason, Boolean flagged, String keyword) {
        if (notBlank(status)) {
            where.append(" AND r.status = ?");
            args.add(status);
        }
        if (notBlank(targetType)) {
            where.append(" AND r.target_type = ?");
            args.add(targetType);
        }
        if (notBlank(reason)) {
            where.append(" AND r.reason = ?");
            args.add(reason);
        }
        if (flagged != null) {
            // 접수자 남용 의심 필터. 여기서도 신고자 id 는 조건에만 쓰고 값으로 꺼내지 않는다.
            where.append(flagged ? " AND EXISTS" : " AND NOT EXISTS")
                    .append("(SELECT 1 FROM anomaly_signals a WHERE a.target_user_id = r.reporter_id"
                            + " AND a.signal_type = 'REPORT_ABUSE')");
        }
        if (notBlank(keyword)) {
            // 대상 라벨 검색 — 본문이 없는 신고라 검색할 수 있는 것은 대상 이름뿐이다.
            where.append(" AND (EXISTS(SELECT 1 FROM users u2 WHERE u2.id = r.target_id"
                    + " AND u2.approved_nickname LIKE ?)"
                    + " OR EXISTS(SELECT 1 FROM challenges c2 WHERE c2.id = r.target_id"
                    + " AND c2.title LIKE ?))");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
    }

    /** 종결 UPDATE — 조건은 호출부가 준다. 조건부라 두 운영자가 동시에 눌러도 하나만 통과한다. */
    private int update(String status, String note, UUID operatorId, Instant at,
                       String condition, Object... conditionArgs) {
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.add(Timestamp.from(at));
        args.add(note);
        args.add(operatorId == null ? null : bytes(operatorId));
        args.addAll(java.util.Arrays.asList(conditionArgs));

        return jdbc.update("UPDATE reports SET status = ?, resolved_at = ?, resolution_note = ?,"
                + " resolved_by = ? WHERE " + condition, args.toArray());
    }

    private AdminDtos.ReportItem itemOf(UUID reportId) {
        return jdbc.query(SELECT_ITEM + " WHERE r.id = ?",
                (rs, i) -> new AdminDtos.ReportItem(
                        uuid(rs.getBytes("id")).toString(),
                        rs.getString("target_type"),
                        uuid(rs.getBytes("target_id")).toString(),
                        rs.getString("target_label"),
                        rs.getString("reason"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant().toString(),
                        rs.getTimestamp("resolved_at") == null
                                ? null : rs.getTimestamp("resolved_at").toInstant().toString(),
                        rs.getInt("target_report_count"),
                        rs.getBoolean("reporter_flagged")),
                bytes(reportId)).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    }

    /**
     * 「콘텐츠만 문제」의 행선 — 거부 처리로 전환하고 수정을 요청한다.
     *
     * <p>자동 심사의 거부와 <b>같은 상태·같은 알림</b>을 쓴다. 운영자 거부만 다른 상태로 두면
     * 재제출·재심사 경로를 한 벌 더 만들어야 하고, 유저에게는 어차피 같은 화면이다.
     */
    private void rejectChallengeContent(UUID challengeId) {
        challengeRepository.findById(challengeId).ifPresent(c -> {
            c.rejectTitle();
            c.rejectDescription();
            if (c.getImageUrl() != null && !c.getImageUrl().isBlank()) c.rejectAndRemoveImage();

            notificationPublisher.publish(NotificationEvent.forChallenge(c.getCreatorId(),
                            NotificationType.MODERATION_REJECTED,
                            "챌린지 내용을 바꿔주세요",
                            "[" + c.publicTitle() + "] 챌린지의 내용이 커뮤니티 기준에 맞지 않아요. "
                                    + "수정 전까지 다른 사람에게는 임시 제목으로 보여요.", c.getId(),
                            // 수정 후 다시 거부될 수 있어 시각을 키에 넣는다.
                            Map.of(NotificationParams.TARGET_KEY, "challenge_text",
                                    NotificationParams.EVENT_KEY,
                                    c.getId() + ":" + Instant.now().toEpochMilli()))
                    .withDeeplink("ruleup://challenges/" + c.getId() + "/edit"));
        });
    }

    /**
     * 스냅샷을 맵으로 읽는다. 문자열 그대로 내리면 콘솔이 JSON 을 <b>문자열 하나</b>로 받아
     * 다시 파싱해야 한다 — 스냅샷 형태가 대상 종류마다 다르므로 그 파싱은 클라이언트에 둘 일이 아니다.
     */
    private Map<String, Object> snapshotOf(String payload) {
        if (payload == null || payload.isBlank()) return Map.of();
        try {
            return JSON.readValue(payload, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (RuntimeException e) {
            // 스냅샷이 깨졌다고 검토를 막지 않는다 — 나머지 판단 재료는 그대로 쓸 수 있다.
            log.warn("신고 스냅샷을 읽지 못했다. 원문을 그대로 내린다.", e);
            return Map.of("raw", payload);
        }
    }

    /**
     * 스냅샷에서 이미지 주소만 골라낸다. 키 이름은 접수 측({@code BlockService})이 고정해 둔 값이다 —
     * 유저 신고는 프로필 사진, 챌린지 신고는 대표 이미지다.
     */
    private List<String> imageUrlsIn(Map<String, Object> snapshot) {
        List<String> urls = new ArrayList<>();
        for (String key : List.of("profileImageUrl", "imageUrl")) {
            Object value = snapshot.get(key);
            if (value instanceof String url && !url.isBlank()) urls.add(url);
        }
        return urls;
    }

    private List<AdminDtos.ReasonCount> reasonCounts(String concatenated) {
        if (concatenated == null || concatenated.isBlank()) return List.of();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String reason : concatenated.split(",")) counts.merge(reason, 1, Integer::sum);
        return counts.entrySet().stream()
                .map(e -> new AdminDtos.ReasonCount(e.getKey(), e.getValue())).toList();
    }

    private List<String> hexIds(String concatenated) {
        if (concatenated == null || concatenated.isBlank()) return List.of();
        return java.util.Arrays.stream(concatenated.split(","))
                .map(this::hexToUuid).map(UUID::toString).toList();
    }

    private UUID hexToUuid(String hex) {
        return UUID.fromString(hex.toLowerCase().replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }

    private String note(AdminDtos.ResolveRequest request) {
        return (request == null || request.note() == null || request.note().isBlank())
                ? null : request.note().trim();
    }

    private boolean notBlank(String s) {
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
