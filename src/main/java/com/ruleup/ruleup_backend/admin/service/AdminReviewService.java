package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.domain.AdminAuditLog;
import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 신고 검토 — 백오피스 백엔드 4-2.
 *
 * <p><b>적재된 것을 읽고 상태만 종결한다.</b> 신고 접수와 스냅샷은 방 내부 모듈 소유이며
 * <b>스냅샷은 수정하지 않는다</b> — 원본이 바뀌어도 접수 시점의 값으로 판단해야 한다.
 *
 * <p>신고자 신원은 어떤 응답에도 넣지 않는다. 피신고자에게도 방장에게도 공개하지 않는 값이라,
 * 조회 SQL 에서 아예 뽑지 않는 편이 실수를 구조적으로 막는다.
 */
@Service
@RequiredArgsConstructor
public class AdminReviewService {

    private final JdbcTemplate jdbc;
    private final AdminAuditService auditService;

    /** 대상 단위로 묶은 검토 큐. 정렬은 접수 순이며 <b>처리 기한은 없다</b>. */
    @Transactional(readOnly = true)
    public AdminDtos.ReportQueueResponse queue(UUID operatorId) {
        auditService.allowed(operatorId, AdminAction.REPORT_QUEUE_VIEW, null, null, null);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.target_type, r.target_user_id, r.target_challenge_id,
                       COUNT(*) AS cnt, MIN(r.created_at) AS first_at,
                       GROUP_CONCAT(HEX(r.id)) AS ids,
                       MAX(u.approved_nickname) AS nickname, MAX(c.title) AS title
                  FROM reports r
                  LEFT JOIN users u ON u.id = r.target_user_id
                  LEFT JOIN challenges c ON c.id = r.target_challenge_id
                 WHERE r.status = 'PENDING'
                 GROUP BY r.target_type, r.target_user_id, r.target_challenge_id
                 ORDER BY first_at ASC
                """);

        List<AdminDtos.Group> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean user = "USER".equals(row.get("target_type"));
            byte[] targetId = (byte[]) (user ? row.get("target_user_id") : row.get("target_challenge_id"));
            if (targetId == null) continue;
            items.add(new AdminDtos.Group(
                    (String) row.get("target_type"),
                    uuid(targetId).toString(),
                    (String) (user ? row.get("nickname") : row.get("title")),
                    ((Number) row.get("cnt")).intValue(),
                    String.valueOf(row.get("first_at")),
                    hexIds((String) row.get("ids"))));
        }
        return new AdminDtos.ReportQueueResponse(items);
    }

    /**
     * 신고 상세. 스냅샷 열람은 <b>개인정보 열람</b>이라 별도 action 으로 남긴다 —
     * "누가 언제 누구의 신고 내용을 봤는지"만 따로 뽑아낼 수 있어야 한다.
     */
    @Transactional(readOnly = true)
    public AdminDtos.ReportDetail detail(UUID operatorId, UUID reportId) {
        auditService.allowed(operatorId, AdminAction.SNAPSHOT_VIEW,
                AdminAuditLog.TargetType.REPORT, reportId, null);

        // reporter_id 를 뽑지 않는다 — 신원은 어떤 경로로도 나가면 안 되는 값이다.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.target_type, r.target_user_id, r.target_challenge_id, r.reason,
                       r.status, r.created_at, s.payload
                  FROM reports r
                  LEFT JOIN report_snapshots s ON s.report_id = r.id
                 WHERE r.id = ?
                """, bytes(reportId));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);

        Map<String, Object> row = rows.getFirst();
        boolean user = "USER".equals(row.get("target_type"));
        byte[] targetId = (byte[]) (user ? row.get("target_user_id") : row.get("target_challenge_id"));
        return new AdminDtos.ReportDetail(
                reportId.toString(),
                (String) row.get("target_type"),
                targetId == null ? null : uuid(targetId).toString(),
                (String) row.get("reason"),
                (String) row.get("status"),
                String.valueOf(row.get("created_at")),
                row.get("payload"));
    }

    /**
     * 검토 결과 확정. <b>종결해도 각 신고자의 개인 차단은 유지</b>된다 — 차단은 제재가 아니라
     * 개인 선택이므로 운영자가 되돌릴 대상이 아니다.
     */
    @Transactional
    public AdminDtos.ResolveResponse resolve(UUID operatorId, UUID reportId,
                                             AdminDtos.ResolveRequest request) {
        String resolution = (request == null) ? null : request.resolution();
        String status = switch (resolution == null ? "" : resolution) {
            case "NO_ACTION" -> "RESOLVED_NO_ACTION";
            case "SANCTIONED" -> "RESOLVED_SANCTIONED";
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST);
        };

        Instant now = Instant.now();
        // 조건부 UPDATE 로 선착순을 가린다 — 두 운영자가 동시에 종결해도 하나만 성공한다.
        int changed = jdbc.update(
                "UPDATE reports SET status = ?, resolved_at = ? WHERE id = ? AND status = 'PENDING'",
                status, java.sql.Timestamp.from(now), bytes(reportId));
        if (changed == 0) throw new BusinessException(ErrorCode.REVIEW_ALREADY_RESOLVED);

        auditService.allowed(operatorId, AdminAction.REPORT_RESOLVE,
                AdminAuditLog.TargetType.REPORT, reportId, status);
        return new AdminDtos.ResolveResponse(reportId.toString(), status, now.toString());
    }

    // ===== 내부 =====

    private List<String> hexIds(String concatenated) {
        if (concatenated == null || concatenated.isBlank()) return List.of();
        return java.util.Arrays.stream(concatenated.split(","))
                .map(this::hexToUuid).map(UUID::toString).toList();
    }

    private UUID hexToUuid(String hex) {
        return UUID.fromString(hex.toLowerCase().replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
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
