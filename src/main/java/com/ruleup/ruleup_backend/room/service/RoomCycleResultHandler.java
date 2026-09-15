package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RoomCycleResultHandler implements OutboxHandler {
    public static final String TYPE = "ROOM_CYCLE_RESULT";
    public record Payload(UUID sourceEventId, UUID userId, UUID challengeId, int cycleNo,
                          int failureStreak, LocalDate cycleStartedOn, Instant effectiveAt, UUID cycleId, long stateVersion, boolean correction,
                          String cycleResult, int successStreak, Instant membershipJoinedAt) {
        public Payload(UUID sourceEventId, UUID userId, UUID challengeId, int cycleNo, int failureStreak, LocalDate start, Instant at) {
            this(sourceEventId,userId,challengeId,cycleNo,failureStreak,start,at,null,0,false,null,0,null);
        }
    }
    private final AutomaticKickService kicks;
    private final ChallengeRepository challenges;
    private final com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository members;
    private final NotificationPublisher notifications;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Override public String type() { return TYPE; }

    @Override public void handle(String json) {
        Payload event = OutboxService.parse(json, Payload.class);
        var challenge = challenges.findByIdForUpdate(event.challengeId()).orElse(null);
        if (challenge == null || members.findByChallengeIdAndUserId(event.challengeId(),event.userId())
                .filter(com.ruleup.ruleup_backend.challenge.domain.ChallengeMember::isActive).isEmpty()) return;
        // Current row/version wins over delayed or corrected cycle events.
        var rows=jdbc.queryForList("SELECT failure_streak_after,version FROM cycle_score_states WHERE user_id=? AND challenge_id=? AND cycle_start_on=? AND closed_at IS NOT NULL",
                com.ruleup.ruleup_backend.score.ScoreKeys.bytes(event.userId()),com.ruleup.ruleup_backend.score.ScoreKeys.bytes(event.challengeId()),event.cycleStartedOn());
        if(rows.isEmpty() || ((Number)rows.getFirst().get("failure_streak_after")).intValue()!=event.failureStreak()
                || (event.stateVersion()>0 && ((Number)rows.getFirst().get("version")).longValue()!=event.stateVersion()))return;
        Instant joinedAt = kicks.latestJoin(event.challengeId(), event.userId());
        if (Instant.MIN.equals(joinedAt)) return;
        LocalDate countedFrom = ChallengeCycle.countFrom(challenge.getStartDate(), joinedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
        if (countedFrom.isAfter(event.cycleStartedOn().minusDays(7L * Math.min(2, event.failureStreak()-1)))) return;
        if (event.failureStreak() >= 3) {
            kicks.enforce(event.challengeId(), event.userId(), AutomaticKickService.Reason.CONSECUTIVE_FAILURE,
                    event.sourceEventId(), event.effectiveAt(), Map.of("cycleNo", event.cycleNo(),
                            "failureStreak", event.failureStreak(), "cycleStartedOn", event.cycleStartedOn().toString()));
        } else if (event.failureStreak() == 2) {
            notifications.publish(NotificationEvent.forChallenge(event.userId(), NotificationType.CONSECUTIVE_FAILURE_WARNING,
                    event.challengeId(), Map.of(NotificationParams.EVENT_KEY, event.sourceEventId().toString(),
                            NotificationParams.CHALLENGE_ID, event.challengeId().toString(),
                            NotificationParams.ROUTINE_ID, challenge.getTemplateId() == null ? event.challengeId().toString() : challenge.getTemplateId().toString())));
        }
    }
}
