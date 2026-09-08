package com.ruleup.ruleup_backend.notification.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SQS 표준 큐 프로듀서.
 *
 * <p><b>묶음 하나가 메시지 하나</b>다. SQS 수신은 호출당 최대 10건이라, 08:00 의 8만 건을 낱개로
 * 보내면 8,000회 수신이 필요하다 — 100건 묶음을 유지하는 것이 처리량의 전제다(백엔드 9절).
 *
 * <p>본문에 <b>렌더링 결과를 다 담는다.</b> 컨슈머가 {@code notifications} 를 다시 읽지 않게
 * 하려는 것이고, 그래서 큐 본문에 알림 문구가 들어간다 — SSE-SQS 를 켜고 보관 기간을
 * 기본값(4일)에서 늘리지 않는 이유다(10절).
 *
 * <p>FIFO 가 아니다. 그룹당 처리량 제한이 있고, 알림 센터가 {@code id} 정렬이라 화면 순서는
 * 이미 보장된다. 08:00 소진 때 트레이 순서가 몇 초 범위에서 섞이는 것은 수용한다.
 */
@Slf4j
public class SqsNotificationQueue implements NotificationQueue {

    /** SQS 메시지 본문 상한 256KB. 100건 ≈ 40KB 라 여유가 있지만 넘으면 쪼갠다. */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    /**
     * 큐 본문 전용 매퍼 — 웹 계층의 {@code ObjectMapper} 를 빌려 쓰지 않는다.
     * 응답 직렬화 설정(널 제외·필드 이름 전략)이 바뀌면 <b>컨슈머가 읽는 형식이 조용히 달라진다</b>.
     */
    private static final ObjectMapper OM = new ObjectMapper();

    private final SqsClient sqs;
    private final String queueUrl;

    public SqsNotificationQueue(SqsClient sqs, String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public void enqueue(List<NotificationMessage> messages) {
        if (messages.isEmpty()) return;

        String body = serialize(messages);
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            // 본문이 한도를 넘으면 반으로 갈라 다시 시도한다. 1건까지 줄여도 넘으면 그건
            // 본문 길이 제한(500자)이 깨진 것이라 로그로 드러나야 한다.
            if (messages.size() == 1) {
                log.error("알림 1건이 SQS 본문 한도를 넘는다 — 푸시를 건너뛴다. id={}",
                        messages.getFirst().id());
                return;
            }
            int half = messages.size() / 2;
            enqueue(messages.subList(0, half));
            enqueue(messages.subList(half, messages.size()));
            return;
        }
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody(body).build());
    }

    private String serialize(List<NotificationMessage> messages) {
        try {
            return OM.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            // 직렬화가 깨지면 이 묶음의 푸시만 잃는다. 적재는 이미 커밋돼 있다.
            throw new IllegalStateException("알림 큐 메시지 직렬화 실패", e);
        }
    }
}
