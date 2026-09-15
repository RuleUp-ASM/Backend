package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.RejoinBackoff;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.common.outbox.OutboxDispatcher;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationMuteCleaner;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.ruleup.ruleup_backend.room.service.ChallengeRejoinPolicy.bytes;

/** Owns enforcement and preserved evidence; verification and scoring own the decisions. */
@Service
@RequiredArgsConstructor
public class AutomaticKickService {
    public enum Reason { CHEAT_DETECTED, CONSECUTIVE_FAILURE, PERMISSION_MISSING }
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ChallengeRepository challenges;
    private final ChallengeMemberRepository members;
    private final JdbcTemplate jdbc;
    private final NotificationMuteCleaner mutes;
    private final NotificationPublisher notifications;
    private final ApplicationEventPublisher events;
    private final OutboxService outbox;
    private final OutboxDispatcher dispatcher;

    @Transactional
    public boolean enforce(UUID challengeId, UUID userId, Reason reason, UUID sourceEventId,
                           Instant effectiveAt, Map<String, Object> evidence) {
        var challenge = challenges.findByIdForUpdate(challengeId).orElse(null);
        boolean permanent = reason == Reason.CHEAT_DETECTED;
        if (challenge == null && (!permanent || jdbc.queryForObject(
                "SELECT COUNT(*) FROM challenge_member_history WHERE challenge_id=? AND user_id=?",Integer.class,bytes(challengeId),bytes(userId))==0)) return false;
        var member = members.findForUpdate(challengeId, userId).orElse(null);
        if (!permanent && (member == null || !member.isActive())) return false;
        if (!permanent && effectiveAt != null && latestJoin(challengeId, userId).isAfter(effectiveAt)) return false;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM challenge_kicks WHERE challenge_id=? AND user_id=? AND reason=? AND source_event_id=?",
                Integer.class, bytes(challengeId), bytes(userId), reason.name(), bytes(sourceEventId)) > 0) return false;

        Instant now = Instant.now();
        Integer count = jdbc.query("SELECT kick_count FROM challenge_rejoin_backoffs WHERE challenge_id=? AND user_id=? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : 0, bytes(challengeId), bytes(userId));
        Instant availableAt = permanent ? null : RejoinBackoff.availableAt(now, count);
        Map<String, Object> snapshot = new LinkedHashMap<>(evidence);
        String title = challenge != null ? challenge.publicTitle() : jdbc.query(
                "SELECT title_snapshot FROM challenge_history WHERE challenge_id=?",rs -> rs.next()?rs.getString(1):null,bytes(challengeId));
        snapshot.put("challengeTitle", title);
        snapshot.put("effectiveAt", effectiveAt == null ? null : effectiveAt.toString());
        jdbc.update("INSERT INTO challenge_kicks(id,challenge_id,user_id,reason,source_event_id,evidence,is_permanent,kicked_at,rejoin_available_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?)", bytes(UuidGenerator.generate()), bytes(challengeId), bytes(userId), reason.name(),
                bytes(sourceEventId), JSON.writeValueAsString(snapshot), permanent, Timestamp.from(now),
                availableAt == null ? null : Timestamp.from(availableAt));
        if (!permanent) jdbc.update("INSERT INTO challenge_rejoin_backoffs VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE " +
                "kick_count=VALUES(kick_count),available_at=VALUES(available_at)", bytes(challengeId), bytes(userId), count + 1, Timestamp.from(availableAt));

        if (member != null && member.isActive() && challenge != null) {
            if (challenge.isOwner(userId)) challenge.convertToBotOwner(now);
            if (permanent) member.kickPermanently(reason.name(), now);
            else member.kick(reason.name(), now, availableAt);
            challenge.bumpVersion();
            mutes.clearMute(userId, challengeId);
            events.publishEvent(ChallengeStatsRefreshRequested.of(challengeId, "KICK"));
        } else if (member != null && permanent) member.banFromRejoin();
        notifications.publish(NotificationEvent.of(userId,
                permanent ? NotificationType.CHEAT_DETECTED : NotificationType.CHALLENGE_KICKED,
                Map.of(NotificationParams.EVENT_KEY, sourceEventId.toString(), NotificationParams.REASON, reason.name())));
        if (reason == Reason.PERMISSION_MISSING) {
            outbox.enqueue(PermissionKickScoreHandler.TYPE,
                    new PermissionKickScoreHandler.Payload(userId, challengeId, sourceEventId), "permission-score:" + sourceEventId);
            dispatcher.requestFlush();
        }
        return true;
    }

    public Instant latestJoin(UUID challengeId, UUID userId) {
        return jdbc.query("SELECT MAX(joined_at) FROM challenge_join_events WHERE challenge_id=? AND user_id=?",
                rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : Instant.MIN,
                bytes(challengeId), bytes(userId));
    }
}
