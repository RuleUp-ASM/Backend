package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.score.ScoreService;
import com.ruleup.ruleup_backend.score.domain.IncidentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PermissionKickScoreHandler implements OutboxHandler {
    public static final String TYPE = "PERMISSION_KICK_SCORE";
    private final ScoreService scores;
    public record Payload(UUID userId, UUID challengeId, UUID sourceEventId) {}
    @Override public String type() { return TYPE; }
    @Override public void handle(String payload) {
        var event = OutboxService.parse(payload, Payload.class);
        scores.applyIncident(event.userId(), event.challengeId(), IncidentType.PERMISSION_KICK, event.sourceEventId().toString(), 0);
    }
}
