package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.consumer.BulkPushSender;
import com.ruleup.ruleup_backend.notification.consumer.PushOutcome;
import com.ruleup.ruleup_backend.notification.consumer.PushRequest;
import com.ruleup.ruleup_backend.notification.queue.NotificationMessage;
import com.ruleup.ruleup_backend.notification.queue.NotificationQueue;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/**
 * 큐 대역 — <b>무엇이 큐에 들어갔는가</b>만 본다. 발송 판정은 컨슈머의 몫이다.
 *
 * <p>알림 스위트가 전부 이 <b>같은</b> 설정을 임포트하는 것이 중요하다. 스프링 테스트 컨텍스트는
 * 임포트 집합까지 캐시 키에 넣으므로, 클래스마다 자기 {@code @TestConfiguration} 을 두면
 * 컨텍스트가 그만큼 늘어나 스위트 전체가 힙을 넘긴다.
 */
@TestConfiguration
public class NotificationTestQueue {

    @Bean
    @Primary
    Recording recordingNotificationQueue() {
        return new Recording();
    }

    /**
     * 전송기 대역 — <b>무엇을 어떤 토큰으로 보내려 했는가</b>를 본다.
     *
     * <p>운영 폴백 스텁은 토큰을 버려서, 컨슈머가 토큰을 제대로 해결했는지 볼 자리가 없었다.
     * 폴백과 같이 전부 성공으로 처리하므로 기존 테스트의 동작은 달라지지 않는다.
     */
    @Bean
    @Primary
    RecordingPushSender recordingBulkPushSender() {
        return new RecordingPushSender();
    }

    public static class RecordingPushSender implements BulkPushSender {

        public final List<PushRequest> sent = new ArrayList<>();

        @Override
        public List<PushOutcome> send(List<PushRequest> requests) {
            sent.addAll(requests);
            return requests.stream().map(r -> PushOutcome.success(r.notificationId())).toList();
        }

        public void reset() {
            sent.clear();
        }
    }

    public static class Recording implements NotificationQueue {

        public final List<NotificationMessage> sent = new ArrayList<>();
        public boolean failOnce;

        @Override
        public void enqueue(List<NotificationMessage> messages) {
            if (failOnce) {
                failOnce = false;
                throw new IllegalStateException("큐가 죽은 상황");
            }
            sent.addAll(messages);
        }

        public void reset() {
            sent.clear();
            failOnce = false;
        }
    }
}
