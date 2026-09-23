package com.ruleup.ruleup_backend.notification.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 큐 구현이 없을 때의 폴백 — 로컬·CI 기동을 막지 않기 위한 것이다.
 *
 * <p>여기로 떨어지면 <b>푸시가 나가지 않는다.</b> 적재는 정상이므로 알림 센터는 그대로 동작하고,
 * 고지도 성립한다. 운영에서 이 로그가 보이면 큐 설정이 빠진 것이다.
 */
@Slf4j
@Configuration
public class NotificationQueueFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(NotificationQueue.class)
    NotificationQueue loggingNotificationQueue() {
        return messages -> log.warn(
                "큐 구현이 없어 푸시를 건너뛴다 — 적재는 정상이다. count={}", messages.size());
    }
}
