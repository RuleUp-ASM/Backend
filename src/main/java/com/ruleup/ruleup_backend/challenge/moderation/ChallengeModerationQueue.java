package com.ruleup.ruleup_backend.challenge.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
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
    private volatile boolean stopping;

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

    /**
     * 종료가 시작되면 새 수신을 멈춘다. 컨텍스트 종료 이벤트는 빈 파괴보다 먼저 온다 — SqsClient 가
     * 닫힌 뒤에도 5초 폴러가 돌아 배포 때마다 옛 태스크가 「Connection pool shut down」·
     * RejectedExecutionException 을 ERROR 로 남겼다. 받아 둔 메시지는 가시성 만료 후 새 태스크가 받는다.
     */
    @EventListener(ContextClosedEvent.class)
    public void stop() {
        stopping = true;
    }

    @Scheduled(fixedDelayString = "${app.moderation.queue.poll-delay-ms:5000}")
    public void poll() {
        if (local || queueUrl.isEmpty() || stopping) return;
        try {
            for (Message message : sqs.getObject().receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl).maxNumberOfMessages(1).waitTimeSeconds(1).visibilityTimeout(120)
                    .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT).build()).messages()) {
                handle(message);
            }
        } catch (RuntimeException failure) {
            // 종료 신호와 같은 순간에 시작된 수신은 닫히는 클라이언트에 걸릴 수 있다 — 장애가 아니다.
            if (stopping) return;
            // 클래스 이름만 남기면 enqueue 때처럼 원인이 가려진다 — 메시지까지 남긴다.
            log.error("moderation_receive_failed error={}", failure.toString());
        }
    }

    public void handle(Message message) {
        UUID challengeId = null;
        try {
            MessageBody body = OM.readValue(message.body(), MessageBody.class);
            challengeId = body.challengeId();
            if (body.challengeId() == null || body.targets() == null || body.targets().isEmpty()
                    || body.targets().contains(null)) throw new IllegalArgumentException("Invalid moderation message");
            service.moderate(body.challengeId(), body.targets());
            sqs.getObject().deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle()).build());
        } catch (Exception failure) {
            // 지우지 않은 메시지는 가시성 타임아웃 뒤 다시 오고, 5회째에 DLQ 로 간다(maxReceiveCount=5).
            // 몇 번째 시도인지와 원인·스택을 남겨야 같은 방이 반복 실패할 때 근본 원인을 볼 수 있다.
            log.warn("moderation_consume_failed messageId={} challengeId={} receiveCount={} error={}",
                    message.messageId(), challengeId,
                    message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT),
                    failure.toString(), failure);
        }
    }
}
