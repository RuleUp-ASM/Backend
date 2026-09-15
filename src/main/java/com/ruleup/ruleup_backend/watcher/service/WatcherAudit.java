package com.ruleup.ruleup_backend.watcher.service;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.UUID;
@Slf4j
@Component
@lombok.RequiredArgsConstructor
public class WatcherAudit {
    private final tools.jackson.databind.json.JsonMapper json;
    public void afterCommit(String event, UUID relationId, UUID actorId, String before, String after, String version) {
        String requestId = java.util.Objects.requireNonNullElseGet(MDC.get("requestId"), () -> UUID.randomUUID().toString());
        Instant occurredAt = Instant.now();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void afterCommit() {
                try {
                    log.info("{}", json.writeValueAsString(new Event(event, relationId, actorId,
                            requestId, occurredAt, before, after, "COMMITTED", version)));
                } catch (RuntimeException ignored) { /* Audit transport cannot undo business state. */ }
            }
        });
    }
    private record Event(String eventName, UUID relationId, UUID actorId, String requestId,
                         Instant occurredAt, String before, String after, String result, String consentVersion) {}
}
