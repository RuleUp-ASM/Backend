package com.ruleup.ruleup_backend.report;

import com.ruleup.ruleup_backend.challenge.counter.UserJoinCounterService;
import com.ruleup.ruleup_backend.challenge.domain.RejoinBackoff;
import com.ruleup.ruleup_backend.challenge.repository.UserChallengeCounterRepository;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.llm.LlmClient;
import com.ruleup.ruleup_backend.notification.NotificationService;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportReviewService {
    private final JdbcTemplate jdbc;
    private final LlmClient llm;
    private final NotificationService notificationService;
    private final UserChallengeCounterRepository counterRepository;
    private final UserJoinCounterService joinCounterService;

    private record ReportRow(UUID id, UUID reporterId, String targetType, UUID targetUserId,
                             UUID targetChallengeId, String contextType, String reason, String detail) {}
    private record Verdict(Boolean valid, Boolean behaviorViolation) {}

    @Transactional
    public void review(UUID reportId) {
        ReportRow report = jdbc.query("SELECT id,reporter_id,target_type,target_user_id,target_challenge_id," +
                        "context_type,reason,detail FROM reports WHERE id=? AND review_status='PENDING'",
                rs -> rs.next() ? new ReportRow(uuid(rs.getBytes(1)), uuid(rs.getBytes(2)), rs.getString(3),
                        nullableUuid(rs.getBytes(4)), nullableUuid(rs.getBytes(5)), rs.getString(6),
                        rs.getString(7), rs.getString(8)) : null, bytes(reportId));
        if (report == null || !llm.isConfigured()) return;
        String raw = llm.generateStructured(prompt(report),
                "{\"type\":\"object\",\"properties\":{\"valid\":{\"type\":\"boolean\"}," +
                        "\"behaviorViolation\":{\"type\":\"boolean\"}},\"required\":[\"valid\",\"behaviorViolation\"]}");
        Verdict verdict = raw == null ? null : llm.parseJson(raw, Verdict.class);
        if (verdict == null || verdict.valid() == null) return;
        if (!verdict.valid()) {
            jdbc.update("UPDATE reports SET review_status='INVALID' WHERE id=?", bytes(reportId));
            suspendAbusiveReporter(report.reporterId());
            return;
        }
        jdbc.update("UPDATE reports SET review_status='VALID' WHERE id=?", bytes(reportId));
        applyThresholds(report, Boolean.TRUE.equals(verdict.behaviorViolation()));
    }

    private void applyThresholds(ReportRow report, boolean behaviorViolation) {
        UUID targetUser = report.targetUserId();
        UUID challenge = report.targetChallengeId();
        if (targetUser != null && challenge != null && behaviorViolation) {
            Integer distinct = jdbc.queryForObject("SELECT COUNT(DISTINCT reporter_id) FROM reports " +
                            "WHERE target_user_id=? AND target_challenge_id=? AND review_status='VALID'",
                    Integer.class, bytes(targetUser), bytes(challenge));
            if (distinct != null && distinct >= 5) kickNonOwner(challenge, targetUser);
            if (distinct != null && distinct >= 10) enqueueAdminReview("USER", targetUser, challenge);
        } else if (challenge != null) {
            Integer distinct = jdbc.queryForObject("SELECT COUNT(DISTINCT reporter_id) FROM reports " +
                            "WHERE target_challenge_id=? AND review_status='VALID'",
                    Integer.class, bytes(challenge));
            if (distinct != null && distinct >= 10)
                enqueueAdminReview(report.targetType(), targetUser, challenge);
        }
    }

    private void kickNonOwner(UUID challengeId, UUID targetUserId) {
        // 락 순서를 사용자부터로 맞춘다(가입·방장 강퇴와 동일). 여기만 순서가 달라지면 데드락이 난다.
        counterRepository.ensureRow(targetUserId);
        counterRepository.lockCount(targetUserId);

        var row = jdbc.query("SELECT role,status,kick_count FROM challenge_members " +
                        "WHERE challenge_id=? AND user_id=? FOR UPDATE",
                rs -> rs.next() ? new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3)} : null,
                bytes(challengeId), bytes(targetUserId));
        if (row == null || "OWNER".equals(row[0]) || !"ACTIVE".equals(row[1])) return;
        Instant now = Instant.now();
        // 방장 재량 강퇴와 동일한 배수 백오프(제재 정책 §4.3) — 사유와 무관하게 같은 규칙.
        Instant rejoinAt = RejoinBackoff.availableAt(now, (Integer) row[2]);
        jdbc.update("UPDATE challenge_members SET status='REMOVED',left_type='KICK',left_at=?," +
                        "kick_reason='신고 누적에 따른 자동 조치',kick_count=kick_count+1,rejoin_available_at=? " +
                        "WHERE challenge_id=? AND user_id=? AND status='ACTIVE'",
                Timestamp.from(now), Timestamp.from(rejoinAt),
                bytes(challengeId), bytes(targetUserId));
        jdbc.update("UPDATE challenges SET participant_count=GREATEST(0,participant_count-1),version=version+1 WHERE id=?",
                bytes(challengeId));
        // 동시 참여 슬롯도 함께 돌려준다 — 안 하면 강퇴당한 사용자가 영구히 슬롯 하나를 잃는다.
        // 방금 REMOVED 로 바꾼 것이 반영돼야 하므로 반드시 <b>같은 트랜잭션</b>에서 센다
        // (새 트랜잭션으로 재계산하면 미커밋 강퇴가 안 보여 옛값을 쓴다).
        counterRepository.setCount(targetUserId, joinCounterService.countActiveSlots(targetUserId));
        notificationService.notify(targetUserId, NotificationType.CHALLENGE_MEMBER_KICKED,
                "챌린지에서 내보내졌어요", "유효한 신고가 누적되어 자동 조치되었습니다.");
    }

    private void enqueueAdminReview(String targetType, UUID targetUser, UUID challenge) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM report_admin_review_queue WHERE target_type=? " +
                        "AND (target_user_id <=> ?) AND (target_challenge_id <=> ?) AND status='PENDING'",
                Integer.class, targetType, bytesOrNull(targetUser), bytesOrNull(challenge));
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO report_admin_review_queue " +
                        "(id,target_type,target_user_id,target_challenge_id) VALUES (?,?,?,?)",
                bytes(UuidGenerator.generate()), targetType, bytesOrNull(targetUser), bytesOrNull(challenge));
    }

    private void suspendAbusiveReporter(UUID reporterId) {
        Integer invalid = jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE reporter_id=? " +
                        "AND review_status='INVALID' AND created_at>=?", Integer.class,
                bytes(reporterId), Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        if (invalid != null && invalid >= 3) {
            jdbc.update("INSERT INTO report_suspensions (user_id,suspended_until,reason) VALUES (?,?,?) " +
                            "ON DUPLICATE KEY UPDATE suspended_until=VALUES(suspended_until),reason=VALUES(reason)",
                    bytes(reporterId), Timestamp.from(Instant.now().plus(Duration.ofDays(7))), "INVALID_REPORT_ABUSE");
        }
    }

    private String prompt(ReportRow report) {
        return "커뮤니티 신고가 실제 정책 위반을 구체적으로 설명하는지 판정하라. 허위·감정적 비난은 valid=false. " +
                "욕설·괴롭힘·스팸·위협 등 사용자 행동 위반이면 behaviorViolation=true. JSON만 반환. " +
                "context=" + report.contextType() + ", reason=" + report.reason() + ", detail=" + report.detail();
    }

    private static byte[] bytesOrNull(UUID id) { return id == null ? null : bytes(id); }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private static UUID nullableUuid(byte[] bytes) { return bytes == null ? null : uuid(bytes); }
    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
