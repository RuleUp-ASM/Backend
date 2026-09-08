package com.ruleup.ruleup_backend.notification;

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
