package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.common.event.PermissionGapDetected;
import com.ruleup.ruleup_backend.common.outbox.OutboxDispatcher;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.push.service.PushOutboxService;
import com.ruleup.ruleup_backend.room.service.PermissionKickHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.*;
import java.util.Map;
import java.util.UUID;

/** Verification owns the two-cycle waiting decision. Notice creation and waiting start are atomic. */
@Service
@RequiredArgsConstructor
public class PermissionWaitService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final ChallengeRepository challenges;
    private final NotificationPublisher notifications;
    private final PushOutboxService pushes;
    private final OutboxService outbox;
    private final OutboxDispatcher dispatcher;

    @EventListener
    @Transactional
    public void detected(PermissionGapDetected event) {
        var challenge = challenges.findById(event.challengeId()).orElse(null);
        if (challenge == null) return;
        // A previous membership's unresolved wait cannot be reused after rejoining.
        jdbc.update("UPDATE verification_permission_waits w SET resolved_at=UTC_TIMESTAMP(6) WHERE challenge_id=? AND user_id=? " +
                "AND signal_type=? AND first_observed_at<(SELECT MAX(joined_at) FROM challenge_join_events e WHERE e.challenge_id=w.challenge_id AND e.user_id=w.user_id)",
                bytes(event.challengeId()),bytes(event.userId()),event.signalType());
        // A partial current cycle is not a full waiting cycle.
        LocalDate from = ChallengeCycle.countFrom(challenge.getStartDate(), event.detectedAt().atZone(KST).toLocalDate());
        pushes.enqueuePermissionGap(event.userId(), event.challengeId(), event.targetDate(), event.signalType(), event.detectedAt());
        notifications.publish(NotificationEvent.of(event.userId(), NotificationType.PERMISSION_REGRANT_REQUIRED,
                Map.of(NotificationParams.EVENT_KEY, event.challengeId() + ":" + event.signalType() + ":" + event.targetDate(),
                        NotificationParams.CHALLENGE_ID, event.challengeId().toString(), NotificationParams.PERMISSION, event.signalType())));
        jdbc.update("INSERT INTO verification_permission_waits(challenge_id,user_id,signal_type,source_event_id,first_observed_at,waiting_from_on) " +
                "VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " +
                "source_event_id=IF(resolved_at IS NOT NULL,VALUES(source_event_id),source_event_id)," +
                "first_observed_at=IF(resolved_at IS NOT NULL,VALUES(first_observed_at),first_observed_at)," +
                "waiting_from_on=IF(resolved_at IS NOT NULL,VALUES(waiting_from_on),waiting_from_on)," +
                "dispatched_at=IF(resolved_at IS NOT NULL,NULL,dispatched_at),resolved_at=NULL",
                bytes(event.challengeId()),bytes(event.userId()),event.signalType(),bytes(UuidGenerator.generate()),Timestamp.from(event.detectedAt()),from);
    }

    public record MeasurementReceived(UUID challengeId, UUID userId, String method, Instant measuredAt) {}

    @EventListener
    @Transactional
    public void received(MeasurementReceived event) {
        jdbc.update("UPDATE verification_permission_waits SET resolved_at=UTC_TIMESTAMP(6) WHERE challenge_id=? AND user_id=? " +
                "AND signal_type=? AND resolved_at IS NULL AND first_observed_at<?",
                bytes(event.challengeId()),bytes(event.userId()),event.method(),Timestamp.from(event.measuredAt()));
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void publishDue() {
        LocalDate today = LocalDate.now(KST);
        var due = jdbc.query("SELECT challenge_id,user_id,signal_type,source_event_id,waiting_from_on FROM verification_permission_waits " +
                "WHERE resolved_at IS NULL AND dispatched_at IS NULL AND waiting_from_on<=? ORDER BY waiting_from_on LIMIT 500 FOR UPDATE SKIP LOCKED",
                (rs,n) -> new PermissionKickHandler.Payload(uuid(rs.getBytes(1)),uuid(rs.getBytes(2)),rs.getString(3),
                        uuid(rs.getBytes(4)),rs.getDate(5).toLocalDate()),today.minusDays(14));
        for (var event : due) {
            outbox.enqueue(PermissionKickHandler.TYPE,event,"permission-kick:" + event.sourceEventId());
            jdbc.update("UPDATE verification_permission_waits SET dispatched_at=UTC_TIMESTAMP(6) WHERE challenge_id=? AND user_id=? AND signal_type=?",
                    bytes(event.challengeId()),bytes(event.userId()),event.method());
        }
        if (!due.isEmpty()) dispatcher.requestFlush();
    }

    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] value) { var b=ByteBuffer.wrap(value); return new UUID(b.getLong(),b.getLong()); }
}
