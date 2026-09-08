package com.ruleup.ruleup_backend.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleup.ruleup_backend.notification.NotificationWindow;
import com.ruleup.ruleup_backend.notification.queue.NotificationMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SQS 컨슈머 — 큐에서 묶음을 꺼내 {@link NotificationDispatcher} 에 넘긴다.
 *
 * <h4>야간 보류는 가시성 타임아웃 연장이 아니라 수신 정지다</h4>
 * 가시성을 늘리면 {@code ApproximateReceiveCount} 가 올라가 <b>정상 메시지가 DLQ 로 빠진다</b>.
 * 수신을 아예 안 하면 카운트가 오르지 않는다. 각 태스크가 {@code Clock} 으로 KST 21:00~08:00 을
 * 판단해 폴링 루프를 쉬고, 메시지는 큐에 그대로 남았다가 08:00 에 집힌다.
 *
 * <p>다만 <b>야간 0건을 실제로 보장하는 것은 정지가 아니라 발송 직전의 재검증</b>이다.
 * 배포 타이밍이나 시계 오차로 21시 직후에 메시지를 받아도 판정에서 막히고, 이때는 메시지를
 * 삭제하지 않고 가시성만 초기화해 다음 수신으로 넘긴다.
 *
 * <h4>Graceful shutdown</h4>
 * SIGTERM 을 받으면 새 수신을 멈추고 처리 중 묶음만 마무리한다. 미처리 메시지는 가시성 만료 후
 * 다른 태스크가 받는다.
 */
@Slf4j
@Component
// 프로듀서 설정과 **같은 조건**이어야 한다. @ConditionalOnProperty 는 빈 문자열도 존재로 보므로,
// 그걸 쓰면 큐가 없는 환경에서 이 빈만 살아나 SqsClient 를 못 찾고 컨텍스트가 죽는다.
@ConditionalOnExpression("!'${app.notification.queue.url:}'.trim().isEmpty()")
public class NotificationConsumer {

    /** 롱 폴링 — 빈 응답 비용을 줄인다. */
    private static final int WAIT_SECONDS = 20;

    /** SQS 수신은 <b>호출당 최대 10건</b>이다. 묶음이 처리량의 전제인 이유. */
    private static final int MAX_MESSAGES = 10;

    /** 야간에 쉬는 간격. 08:00 경계를 놓치지 않을 만큼만 짧게 둔다. */
    private static final long NIGHT_SLEEP_MILLIS = 30_000L;

    private static final ObjectMapper OM = new ObjectMapper();

    private final SqsClient sqs;
    private final String queueUrl;
    private final NotificationDispatcher dispatcher;
    private final boolean enabled;

    private volatile boolean running = true;
    private Thread worker;

    public NotificationConsumer(SqsClient notificationSqsClient,
                                @Value("${app.notification.queue.url}") String queueUrl,
                                @Value("${app.notification.queue.consumer-enabled:true}") boolean enabled,
                                NotificationDispatcher dispatcher) {
        this.sqs = notificationSqsClient;
        this.queueUrl = queueUrl.trim();
        this.dispatcher = dispatcher;
        this.enabled = enabled;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("알림 컨슈머를 켜지 않는다 — 적재는 계속되고 푸시만 멈춘다.");
            return;
        }
        worker = new Thread(this::loop, "notification-consumer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 정지 — 새 수신을 멈추고 처리 중 묶음만 마무리한다. Fargate {@code stopTimeout} 120초 안에
     * 끝나야 하므로 진행 중 작업을 기다리는 시간을 짧게 잡는다.
     */
    @PreDestroy
    void stop() {
        running = false;
        if (worker == null) return;
        worker.interrupt();
        try {
            worker.join(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        while (running) {
            try {
                if (NotificationWindow.isNight(Instant.now())) {
                    // 수신 자체를 하지 않는다 — 가시성을 늘리면 receiveCount 가 올라 DLQ 로 샌다.
                    Thread.sleep(NIGHT_SLEEP_MILLIS);
                    continue;
                }
                poll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 큐가 잠깐 죽어도 루프는 살아 있어야 한다. 메시지는 가시성 만료 후 다시 온다.
                log.warn("알림 컨슈머 폴링 실패 — 계속 돈다: {}", e.toString());
                sleepQuietly();
            }
        }
    }

    /** 한 번 수신해 처리한다. 테스트가 루프 없이 한 사이클만 돌릴 수 있게 열어 둔다. */
    public int poll() {
        List<Message> received = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(MAX_MESSAGES)
                .waitTimeSeconds(WAIT_SECONDS)
                .build()).messages();

        int handled = 0;
        for (Message sqsMessage : received) handled += handle(sqsMessage);
        return handled;
    }

    private int handle(Message sqsMessage) {
        List<NotificationMessage> batch;
        try {
            batch = List.of(OM.readValue(sqsMessage.body(), NotificationMessage[].class));
        } catch (Exception e) {
            // 읽을 수 없는 메시지를 남겨 두면 5회 재수신 후 DLQ 로 간다 — 그게 맞는 자리다.
            log.error("알림 큐 메시지를 해석할 수 없다 — DLQ 로 보낸다: {}", e.toString());
            return 0;
        }

        List<NotificationDispatcher.DispatchOutcome> outcomes =
                dispatcher.dispatch(batch, Instant.now());

        Map<Boolean, Long> byDeletable = outcomes.stream()
                .collect(Collectors.partitioningBy(
                        NotificationDispatcher.DispatchOutcome::deletable, Collectors.counting()));

        // 묶음은 메시지 하나다. 하나라도 남길 것이 있으면 메시지를 지우지 않고 다시 받는다 —
        // at-least-once 라 이미 나간 건이 한 번 더 갈 수 있고, 클라이언트가 notificationId 를
        // 알림 tag 로 써서 표시 단계에서 흡수한다(공통 7절).
        if (byDeletable.getOrDefault(false, 0L) == 0L) {
            sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl).receiptHandle(sqsMessage.receiptHandle()).build());
        } else {
            // 가시성만 초기화해 다음 수신으로 넘긴다. 야간 보류가 이 경로다.
            sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                    .queueUrl(queueUrl).receiptHandle(sqsMessage.receiptHandle())
                    .visibilityTimeout(0).build());
        }
        return batch.size();
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
