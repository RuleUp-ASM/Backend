package com.ruleup.ruleup_backend.challenge.moderation;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Creation/PATCH commit before publication. Pending timestamps recover a lost application event. */
@Component
@RequiredArgsConstructor
public class ChallengeModerationEventListener {
    private static final Logger log = LoggerFactory.getLogger(ChallengeModerationEventListener.class);
    private final ChallengeModerationQueue queue;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModerationRequested(ChallengeModerationRequested event) {
        try { queue.publish(event.challengeId()); }
        catch (RuntimeException failure) {
            log.warn("moderation_publish_failed challengeId={} error={}", event.challengeId(), failure.getClass().getSimpleName());
        }
    }
}
