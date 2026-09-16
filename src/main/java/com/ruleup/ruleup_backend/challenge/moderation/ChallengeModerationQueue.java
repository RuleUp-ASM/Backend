package com.ruleup.ruleup_backend.challenge.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.UUID;

import static com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationSnapshot.Target;

/** Standard queue: acknowledge only after decisions commit; failures are retried by SQS/DLQ. */
@Component
public class ChallengeModerationQueue {
    private static final Logger log = LoggerFactory.getLogger(ChallengeModerationQueue.class);
    private static final ObjectMapper OM = new ObjectMapper();
    public record MessageBody(UUID challengeId, List<Target> targets) {}
    private final ObjectProvider<SqsClient> sqs;
    private final String queueUrl;
    private final boolean local;
    private final ChallengeModerationStore store;
    private final ChallengeModerationService service;

    public ChallengeModerationQueue(@Qualifier("challengeModerationSqsClient") ObjectProvider<SqsClient> sqs,
                                    @Value("${app.moderation.queue.url:}") String queueUrl,
                                    @Value("${app.moderation.local:false}") boolean local,
                                    ChallengeModerationStore store, ChallengeModerationService service) {
        this.sqs = sqs; this.queueUrl = queueUrl.trim(); this.local = local;
        this.store = store; this.service = service;
    }

    public void publish(UUID id) {
        ChallengeModerationSnapshot snapshot = store.read(id);
        if (snapshot == null || snapshot.targets().isEmpty()) return;
        if (local) { service.moderate(id, snapshot.targets()); return; }
        try {
            SqsClient client = sqs.getIfAvailable();
            if (client == null) throw new IllegalStateException("Moderation queue is not configured");
            client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl)
                    .messageBody(OM.writeValueAsString(new MessageBody(id, snapshot.targets()))).build());
            store.markEnqueued(snapshot);
            log.info("moderation_enqueued challengeId={} targets={}", id, snapshot.targets());
        } catch (Exception failure) {
            // No success marker: the five-minute recovery scan will retry after ten minutes.
            // Keep the message, not just the type: "IllegalStateException" alone hid a missing
            // MODERATION_QUEUE_URL behind a generic-looking error for days (QA 2026-09-16).
            log.error("moderation_enqueue_failed challengeId={} error={}", id, failure.toString());
        }
    }

    /** Publishing is possible only in-process (local) or with a reachable queue client. */
    public boolean configured() {
        return local || (!queueUrl.isEmpty() && sqs.getIfAvailable() != null);
    }

    @Scheduled(fixedDelayString = "${app.moderation.queue.poll-delay-ms:5000}")
    public void poll() {
        if (local || queueUrl.isEmpty()) return;
        try {
            for (Message message : sqs.getObject().receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl).maxNumberOfMessages(1).waitTimeSeconds(1).visibilityTimeout(120).build()).messages()) {
                handle(message);
            }
        } catch (RuntimeException failure) {
            log.error("moderation_receive_failed error={}", failure.getClass().getSimpleName());
        }
    }

    public void handle(Message message) {
        try {
            MessageBody body = OM.readValue(message.body(), MessageBody.class);
            if (body.challengeId() == null || body.targets() == null || body.targets().isEmpty()
                    || body.targets().contains(null)) throw new IllegalArgumentException("Invalid moderation message");
            service.moderate(body.challengeId(), body.targets());
            sqs.getObject().deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle()).build());
        } catch (Exception failure) {
            log.warn("moderation_consume_failed messageId={} error={}", message.messageId(),
                    failure.getClass().getSimpleName());
        }
    }
}
