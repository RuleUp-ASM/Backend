package com.ruleup.ruleup_backend.notification.consumer;

import com.ruleup.ruleup_backend.notification.queue.NotificationMessage;

import java.util.List;
import java.util.UUID;

/**
 * FCM 한 건의 전송 요청 — <b>토큰마다 payload 가 다르다</b>.
 *
 * <p>{@code sendEachForMulticast} 를 쓰지 않는 이유가 이것이다. 알림마다
 * {@code notificationId}·{@code deeplink} 가 달라 payload 를 공유할 수 없다.
 */
public record PushRequest(NotificationMessage message, List<String> tokens) {

    public UUID notificationId() {
        return message.id();
    }
}
