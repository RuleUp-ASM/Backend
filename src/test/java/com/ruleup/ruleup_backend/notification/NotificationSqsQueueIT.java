package com.ruleup.ruleup_backend.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.queue.NotificationQueue;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQS 프로듀서 — <b>실제 SQS API(LocalStack)</b>로 끝까지 검증한다.
 *
 * <p>목으로는 알 수 없는 것을 잡는다. 묶음이 실제로 <b>메시지 하나</b>로 들어가는지,
 * 본문에 렌더링 결과가 다 실려 컨슈머가 {@code notifications} 를 다시 읽지 않아도 되는지,
 * 256KB 한도를 넘지 않는지는 진짜 큐를 상대해야 확인된다.
 *
 * <p>묶음은 처리량의 전제다 — <b>SQS 수신은 호출당 최대 10건</b>이라, 08:00 의 8만 건을 낱개로
 * 보내면 8,000회 수신이 필요하다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationSqsQueueIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.SQS);

    static String queueUrl;

    static {
        LOCALSTACK.start();
        try (SqsClient bootstrap = SqsClient.builder()
                .region(software.amazon.awssdk.regions.Region.of(LOCALSTACK.getRegion()))
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
                        .create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials
                                .create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build()) {
            queueUrl = bootstrap.createQueue(CreateQueueRequest.builder()
                    .queueName("ruleup-notifications-test").build()).queueUrl();
        }
    }

    @DynamicPropertySource
    static void sqsProperties(DynamicPropertyRegistry registry) {
        registry.add("app.notification.queue.url", () -> queueUrl);
        registry.add("app.notification.queue.region", LOCALSTACK::getRegion);
        registry.add("app.notification.queue.endpoint",
                () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS).toString());
        registry.add("app.notification.queue.access-key", LOCALSTACK::getAccessKey);
        registry.add("app.notification.queue.secret-key", LOCALSTACK::getSecretKey);
        // 컨슈머를 켜지 않는다. 이 스위트는 **무엇이 큐에 들어갔는가**만 보는데, 컨슈머가 돌면
        // 검증하기 전에 메시지를 집어 삼킨다. 수신 쪽은 NotificationConsumerIT 가 맡는다.
        registry.add("app.notification.queue.consumer-enabled", () -> "false");
    }

    @Autowired NotificationPublisher publisher;
    @Autowired NotificationQueue queue;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired SqsClient sqsClient;

    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void drain() {
        sqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl).build());
    }

    private UUID newUser() {
        String tag = "sqs" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com", "닉" + SEQ.get(), null, List.of())).getId();
    }

    private List<Message> receiveAll() {
        List<Message> all = new ArrayList<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            List<Message> batch = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1).build())
                    .messages();
            if (batch.isEmpty() && !all.isEmpty()) break;
            all.addAll(batch);
        }
        return all;
    }

    @Test
    @DisplayName("SQS 구현이 기본 큐로 주입된다 — 설정이 있으면 로그 폴백으로 떨어지지 않는다")
    void sqsImplementationIsWired() {
        assertThat(queue.getClass().getSimpleName()).isEqualTo("SqsNotificationQueue");
    }

    @Test
    @DisplayName("적재 커밋 후 메시지가 큐에 들어가고 본문에 렌더링 결과가 다 실린다")
    void messageCarriesRenderedPayload() throws Exception {
        UUID userId = newUser();
        txTemplate.executeWithoutResult(t -> publisher.publish(NotificationEvent.of(
                userId, NotificationType.APPEAL_RESULT, "이의 결과", "인용됐어요",
                Map.of(NotificationParams.APPEAL_ID, "ap-" + SEQ.incrementAndGet()))));

        List<Message> messages = receiveAll();
        assertThat(messages).hasSize(1);

        JsonNode batch = om.readTree(messages.getFirst().body());
        assertThat(batch.isArray()).as("본문은 알림 묶음 배열이다").isTrue();
        assertThat(batch).hasSize(1);

        JsonNode item = batch.get(0);
        assertThat(item.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(item.get("type").asText()).isEqualTo("APPEAL_RESULT");
        assertThat(item.get("toggleGroup").asText()).isEqualTo("ACCOUNT");
        assertThat(item.get("tab").asText()).isEqualTo("NOTIFICATION");
        assertThat(item.get("title").asText()).isEqualTo("이의 결과");
        assertThat(item.get("body").asText()).isEqualTo("인용됐어요");
        assertThat(item.get("deeplink").asText()).isEqualTo("ruleup://me/appeals");
        assertThat(item.get("id").asText()).isNotBlank();
    }

    @Test
    @DisplayName("100건 묶음이 메시지 하나로 들어간다 — 수신이 호출당 10건이라 묶음이 처리량의 전제다")
    void hundredNotificationsBecomeOneMessage() throws Exception {
        List<NotificationEvent> events = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            events.add(NotificationEvent.of(newUser(), NotificationType.APPEAL_RESULT,
                    "이의 결과", "본문",
                    Map.of(NotificationParams.APPEAL_ID, "bulk-" + SEQ.incrementAndGet())));
        }
        txTemplate.executeWithoutResult(t -> publisher.publishAll(events));

        List<Message> messages = receiveAll();
        assertThat(messages).hasSize(1);
        assertThat(om.readTree(messages.getFirst().body())).hasSize(100);
    }

    @Test
    @DisplayName("101건이면 메시지 두 개로 나뉜다 — 묶음 상한이 100이다")
    void overflowSplitsIntoTwoMessages() {
        List<NotificationEvent> events = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            events.add(NotificationEvent.of(newUser(), NotificationType.APPEAL_RESULT,
                    "이의 결과", "본문",
                    Map.of(NotificationParams.APPEAL_ID, "split-" + SEQ.incrementAndGet())));
        }
        txTemplate.executeWithoutResult(t -> publisher.publishAll(events));

        assertThat(receiveAll()).hasSize(2);
    }

    @Test
    @DisplayName("공지는 큐에 들어가지 않는다 — pushable=false 라 적재만 된다")
    void announcementNeverReachesTheQueue() {
        UUID userId = newUser();
        txTemplate.executeWithoutResult(t -> publisher.publish(NotificationEvent.of(
                userId, NotificationType.ANNOUNCEMENT, "점검 안내", "본문",
                Map.of(NotificationParams.ANNOUNCEMENT_ID, "an-" + SEQ.incrementAndGet()))));

        assertThat(receiveAll()).isEmpty();
    }
}
