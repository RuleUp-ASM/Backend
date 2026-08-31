package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.domain.NotificationDelivery;
import com.ruleup.ruleup_backend.push.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * FCM 발송 — 결과를 {@link NotificationDelivery} 에 기록하고 끝낸다.
 *
 * <p><b>실패해도 재시도하지 않는다.</b> 알림함이 고지를 대체하므로 중복 푸시 위험을 감수할
 * 이유가 없다. 등록 기기가 없어도 마찬가지다 — 푸시 권한을 거부한 사용자는 알림함으로만 도달한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushSender {

    private final DeviceTokenRepository deviceTokenRepository;
    private final com.ruleup.ruleup_backend.push.PushSender pushSender;

    public void send(Notification notification, NotificationDelivery delivery, Instant now) {
        if (deviceTokenRepository.findByUserId(notification.getUserId()).isEmpty()) {
            // 푸시 권한을 거부했거나 기기가 없는 경우. 실패로 기록하되 고지는 이미 성립해 있다.
            delivery.markFailed(now, "NO_DEVICE_TOKEN");
            return;
        }
        try {
            pushSender.sendDisplay(notification.getUserId(),
                    new com.ruleup.ruleup_backend.push.DisplayPush(
                            notification.getId().toString(),
                            notification.getType(),
                            notification.getTitle(),
                            notification.getBody(),
                            notification.getDeeplink()));
            delivery.markSent(now);
        } catch (RuntimeException e) {
            // 기록만 하고 끝낸다. 여기서 예외를 밖으로 던지면 적재까지 롤백될 수 있다.
            log.warn("푸시 발송 실패 — 재시도하지 않는다. notificationId={} err={}",
                    notification.getId(), e.toString());
            delivery.markFailed(now, e.getClass().getSimpleName());
        }
    }
}
