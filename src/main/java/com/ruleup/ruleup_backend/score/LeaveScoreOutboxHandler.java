package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.score.domain.IncidentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LeaveScoreOutboxHandler implements OutboxHandler {
    public static final String TYPE = "CHALLENGE_LEAVE_SCORE";
    private final ScoreService scores;
    public record Payload(UUID userId, UUID challengeId, String sourceId, int progressWeeks,
                          String verificationMethod, int confirmedDelta, Instant occurredAt) {}
    @Override public String type() { return TYPE; }
    @Override public void handle(String json) {
        Payload event=OutboxService.parse(json,Payload.class);
        if (!"AUTO".equals(event.verificationMethod()) || event.confirmedDelta() == 0) return;
        scores.applyIncident(event.userId(),event.challengeId(), IncidentType.VOLUNTARY_LEAVE,event.sourceId(),event.progressWeeks());
    }
}
