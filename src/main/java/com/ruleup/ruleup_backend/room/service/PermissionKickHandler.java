package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import static com.ruleup.ruleup_backend.room.service.ChallengeRejoinPolicy.bytes;

@Component
@RequiredArgsConstructor
public class PermissionKickHandler implements OutboxHandler {
    public static final String TYPE = "ROOM_PERMISSION_KICK";
    public record Payload(UUID challengeId, UUID userId, String method, UUID sourceEventId, LocalDate waitingFromOn) {}
    private final ChallengeRepository challenges;
    private final JdbcTemplate jdbc;
    private final AutomaticKickService kicks;
    @Override public String type() { return TYPE; }
    @Override public void handle(String json) {
        Payload event = OutboxService.parse(json, Payload.class);
        if (challenges.findByIdForUpdate(event.challengeId()).isEmpty()) return;
        var firstObserved = jdbc.query("SELECT first_observed_at FROM verification_permission_waits WHERE challenge_id=? AND user_id=? " +
                "AND signal_type=? AND source_event_id=? AND resolved_at IS NULL FOR UPDATE",
                rs -> rs.next() ? rs.getTimestamp(1).toInstant() : null,
                bytes(event.challengeId()),bytes(event.userId()),event.method(),bytes(event.sourceEventId()));
        if (firstObserved == null) return;
        if (LocalDate.now(ZoneId.of("Asia/Seoul")).isBefore(event.waitingFromOn().plusDays(14)))
            throw new IllegalStateException("Permission wait has not matured");
        kicks.enforce(event.challengeId(),event.userId(),AutomaticKickService.Reason.PERMISSION_MISSING,
                event.sourceEventId(),firstObserved,Map.of("method",event.method(),"waitingFromOn",event.waitingFromOn().toString(),"waitingCycles",2));
    }
}
