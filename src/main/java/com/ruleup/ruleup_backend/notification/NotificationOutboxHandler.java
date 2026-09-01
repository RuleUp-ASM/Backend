package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 아웃박스 → 알림 적재. 도메인 커밋 이후에만 불린다.
 *
 * <p>멱등성은 알림 타입의 중복 제어({@code dedupWindow})가 맡는다. 필수(A)는 중복 제어를 걸지
 * 않으므로 재시도 시 알림함에 두 줄이 쌓일 수 있으나, <b>필수 고지는 누락보다 중복이 낫다</b> —
 * 법적 고지 의무는 도달로 성립하고 중복 도달로 깨지지 않는다.
 */
@Component
@RequiredArgsConstructor
public class NotificationOutboxHandler implements OutboxHandler {

    private final NotificationPublisher publisher;

    @Override
    public String type() {
        return NotificationPublisher.OUTBOX_TYPE;
    }

    @Override
    public void handle(String payload) {
        publisher.deliver(OutboxService.parse(payload, NotificationEvent.class));
    }
}
