package com.ruleup.ruleup_backend.notification.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * FCM 자격증명이 없을 때의 스텁 — 로컬·CI 기동을 막지 않는다.
 *
 * <p><b>성공으로 처리한다.</b> 실패로 두면 컨슈머가 재시도 대상으로 보고 메시지를 남겨,
 * 자격증명 없는 환경에서 큐가 영원히 비지 않는다.
 */
@Slf4j
@Configuration
public class BulkPushSenderFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(BulkPushSender.class)
    BulkPushSender loggingBulkPushSender() {
        return requests -> {
            requests.forEach(r -> log.debug("[스텁] 푸시 전송 notificationId={} tokens={}",
                    r.notificationId(), r.tokens().size()));
            return requests.stream().map(r -> PushOutcome.success(r.notificationId())).toList();
        };
    }
}
