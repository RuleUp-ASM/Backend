package com.ruleup.ruleup_backend.challenge.moderation;

import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.moderation.ModerationResult;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationSnapshot.Target;

/** Read without a lock, call the provider outside a transaction, then conditionally apply each field. */
@Service
@RequiredArgsConstructor
public class ChallengeModerationService {
    private static final Logger log = LoggerFactory.getLogger(ChallengeModerationService.class);
    private final ChallengeModerationStore store;
    private final ContentModerationClient moderationClient;
    private final ChallengeNameBlocklist blocklist;
    private final NotificationPublisher notificationPublisher;
    private final TransactionTemplate transactionTemplate;

    public void moderate(UUID id) { moderate(id, List.of(Target.values())); }

    public void moderate(UUID id, List<Target> requested) {
        ChallengeModerationSnapshot snapshot = store.read(id);
        if (snapshot == null) return;
        List<Target> targets = snapshot.targets().stream().filter(requested::contains).toList();
        if (targets.isEmpty()) return;
        long started = System.nanoTime();
        Map<Target, ModerationResult> verdicts = new EnumMap<>(Target.class);
        for (Target target : targets) {
            if (target != Target.IMAGE && blocklist.hits(snapshot.content(target)))
                verdicts.put(target, ModerationResult.REJECTED);
        }
        boolean titleNeeded = targets.contains(Target.TITLE) && !verdicts.containsKey(Target.TITLE);
        boolean descriptionNeeded = targets.contains(Target.DESCRIPTION) && !verdicts.containsKey(Target.DESCRIPTION);
        if (titleNeeded || descriptionNeeded) {
            var result = moderationClient.moderateChallengeText(titleNeeded ? snapshot.title() : null,
                    descriptionNeeded ? snapshot.description() : null);
            if (titleNeeded) verdicts.put(Target.TITLE, result.title());
            if (descriptionNeeded) verdicts.put(Target.DESCRIPTION, result.description());
        }
        if (targets.contains(Target.IMAGE)) {
            verdicts.put(Target.IMAGE, snapshot.image() == null || snapshot.image().isBlank()
                    ? ModerationResult.APPROVED : moderationClient.moderateImage(snapshot.image()));
        }
        // Provider errors remain pending. SQS retries only pending fields; committed decisions survive.
        transactionTemplate.executeWithoutResult(tx -> {
            boolean anyRejected = false;
            for (var entry : verdicts.entrySet()) {
                Target target = entry.getKey();
                ModerationResult result = entry.getValue();
                boolean applied = result != ModerationResult.UNAVAILABLE
                        && store.apply(snapshot, target, result.name());
                if (applied && result == ModerationResult.REJECTED) {
                    anyRejected = true;
                    rejectNotification(snapshot, target);
                }
                log.info("moderation_result challengeId={} target={} result={} applied={} latencyMs={}",
                        id, target, result, applied, (System.nanoTime() - started) / 1_000_000);
            }
            if (anyRejected) store.recordRejection(id);
            store.finish(id);
        });
        if (verdicts.containsValue(ModerationResult.UNAVAILABLE))
            throw new IllegalStateException("Moderation provider unavailable");
    }

    private void rejectNotification(ChallengeModerationSnapshot snapshot, Target target) {
        // Owner may have left while the provider was running. Notify the current owner only.
        ChallengeModerationSnapshot current = store.read(snapshot.id());
        if (current == null || current.ownerId() == null) return;
        NotificationType type = target == Target.IMAGE
                ? NotificationType.CHALLENGE_IMAGE_REMOVED : NotificationType.MODERATION_REJECTED;
        notificationPublisher.publish(NotificationEvent.forChallenge(current.ownerId(), type, snapshot.id(),
                Map.of(NotificationParams.VARIANT, "CHALLENGE_TEXT",
                        NotificationParams.CHALLENGE_TITLE, "챌린지",
                        NotificationParams.CHALLENGE_ID, snapshot.id().toString(),
                        NotificationParams.TARGET_KEY, "challenge_" + target.name().toLowerCase(java.util.Locale.ROOT),
                        NotificationParams.EVENT_KEY, UUID.randomUUID().toString()))
                .withDeeplink("ruleup://challenges/" + snapshot.id() + "/edit"));
    }
}
