package com.ruleup.ruleup_backend.report;

import com.ruleup.ruleup_backend.challenge.counter.UserJoinCounterService;
import com.ruleup.ruleup_backend.challenge.domain.RejoinBackoff;
import com.ruleup.ruleup_backend.challenge.repository.UserChallengeCounterRepository;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.llm.LlmClient;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
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
    private final NotificationPublisher notificationPublisher;
    private final UserChallengeCounterRepository counterRepository;
    private final UserJoinCounterService joinCounterService;
    private final ApplicationEventPublisher events;

    private record ReportRow(UUID id, UUID reporterId, String targetType, UUID targetUserId,
                             UUID targetChallengeId, String contextType, String reason, String detail,
                             boolean duplicate) {}
    record Verdict(Boolean valid, Boolean behaviorViolation) {}

    @Transactional
    public void review(UUID reportId) {
        ReportRow report = jdbc.query("SELECT id,reporter_id,target_type,target_user_id,target_challenge_id," +
                        "context_type,reason,detail,duplicate_report FROM reports " +
                        "WHERE id=? AND review_status='PENDING' FOR UPDATE",
                rs -> rs.next() ? new ReportRow(uuid(rs.getBytes(1)), uuid(rs.getBytes(2)), rs.getString(3),
                        nullableUuid(rs.getBytes(4)), nullableUuid(rs.getBytes(5)), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getBoolean(9)) : null, bytes(reportId));
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
        jdbc.update("UPDATE reports SET review_status='VALID',behavior_violation=? WHERE id=?",
                Boolean.TRUE.equals(verdict.behaviorViolation()), bytes(reportId));
        if (!report.duplicate()) applyThresholds(report, Boolean.TRUE.equals(verdict.behaviorViolation()));
    }

    private void applyThresholds(ReportRow report, boolean behaviorViolation) {
        UUID targetUser = report.targetUserId();
        UUID challenge = report.targetChallengeId();
        if (targetUser != null) {
            if (challenge != null && behaviorViolation) {
                Integer distinct = jdbc.queryForObject("SELECT COUNT(DISTINCT reporter_id) FROM reports " +
                                "WHERE target_user_id=? AND target_challenge_id=? AND review_status='VALID' " +
                                "AND behavior_violation=1 AND duplicate_report=0",
                        Integer.class, bytes(targetUser), bytes(challenge));
                if (distinct != null && distinct >= 5) kickNonOwner(challenge, targetUser);
                if (distinct != null && distinct >= 10) enqueueAdminReview("USER", targetUser, challenge);
            }
            return; // 사용자 신고를 챌린지 자체의 콘텐츠 신고 임계치에 섞지 않는다.
        }
        if (challenge != null) {
            Integer distinct = jdbc.queryForObject("SELECT COUNT(DISTINCT reporter_id) FROM reports " +
                            "WHERE target_challenge_id=? AND review_status='VALID' AND duplicate_report=0",
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
        int changed = jdbc.update("UPDATE challenge_members SET status='REMOVED',left_type='KICK',left_at=?," +
                        "kick_reason='신고 누적에 따른 자동 조치',kick_count=kick_count+1,rejoin_available_at=? " +
                        "WHERE challenge_id=? AND user_id=? AND status='ACTIVE'",
                Timestamp.from(now), Timestamp.from(rejoinAt),
                bytes(challengeId), bytes(targetUserId));
        if (changed == 0) return;
        jdbc.update("UPDATE challenges SET participant_count=GREATEST(0,participant_count-1),version=version+1 WHERE id=?",
                bytes(challengeId));
        // 동시 참여 슬롯도 함께 돌려준다 — 안 하면 강퇴당한 사용자가 영구히 슬롯 하나를 잃는다.
        // 방금 REMOVED 로 바꾼 것이 반영돼야 하므로 반드시 <b>같은 트랜잭션</b>에서 센다
        // (새 트랜잭션으로 재계산하면 미커밋 강퇴가 안 보여 옛값을 쓴다).
        counterRepository.setCount(targetUserId, joinCounterService.countActiveSlots(targetUserId));
        events.publishEvent(ChallengeStatsRefreshRequested.of(challengeId, "REPORT_AUTO_KICK"));
        notificationPublisher.publish(NotificationEvent.forChallenge(targetUserId,
                NotificationType.CHALLENGE_KICKED,
                "챌린지에서 내보내졌어요", "유효한 신고가 누적되어 자동 조치되었습니다.", challengeId));
    }

    /**
     * 신고 발생 시 방장에게 보내던 알림은 <b>정책상 폐지</b>됐다(알림 정책 2026-08-25).
     * 방장 권한이 축소되면서 신고는 운영자 검토로만 가고 방장은 개입하지 않는다.
     * 호출부를 지우지 않고 빈 메서드로 남겨 두면 폐지 사실이 드러나지 않으므로 호출도 함께 제거했다.
     */

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
                        "AND review_status='INVALID' AND duplicate_report=0 AND created_at>=?", Integer.class,
                bytes(reporterId), Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        if (invalid != null && invalid >= 3) {
            Integer current = jdbc.query("SELECT suspension_count FROM report_suspensions WHERE user_id=? FOR UPDATE",
                    rs -> rs.next() ? rs.getInt(1) : 0, bytes(reporterId));
            int next = current == null ? 1 : current + 1;
            long days = 7L << Math.min(next - 1, 2); // 1주 → 2주 → 4주(이후 4주 상한)
            jdbc.update("INSERT INTO report_suspensions (user_id,suspended_until,reason,suspension_count) VALUES (?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE suspended_until=VALUES(suspended_until)," +
                            "reason=VALUES(reason),suspension_count=VALUES(suspension_count)",
                    bytes(reporterId), Timestamp.from(Instant.now().plus(Duration.ofDays(days))),
                    "INVALID_REPORT_ABUSE", next);
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
