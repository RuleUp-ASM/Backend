package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
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
                          int failureStreak, LocalDate cycleStartedOn, Instant effectiveAt) {}
    private final AutomaticKickService kicks;
    private final ChallengeRepository challenges;
    private final com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository members;
    private final NotificationPublisher notifications;
    @Override public String type() { return TYPE; }

    @Override public void handle(String json) {
        Payload event = OutboxService.parse(json, Payload.class);
        var challenge = challenges.findByIdForUpdate(event.challengeId()).orElse(null);
        if (challenge == null || members.findByChallengeIdAndUserId(event.challengeId(),event.userId())
                .filter(com.ruleup.ruleup_backend.challenge.domain.ChallengeMember::isActive).isEmpty()) return;
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
