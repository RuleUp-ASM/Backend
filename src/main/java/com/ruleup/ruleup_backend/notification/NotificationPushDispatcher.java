package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.domain.NotificationDelivery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

/**
 * 적재 커밋 이후의 즉시 푸시.
 *
 * <p><b>이 경로는 지연을 줄일 뿐 보장이 아니다.</b> 여기서 실패하거나 콜백 자체가 유실돼도
 * {@code notification_deliveries.sent_at} 이 null 로 남아 있고, {@link NotificationBatch} 의
 * 보정 배치가 같은 행을 다시 집는다 — 유실을 막는 것은 그쪽이다.
 *
 * <p>커밋 전에 보내지 않는 이유는 하나다: 롤백된 알림의 푸시가 이미 잠금화면에 떠 있으면
 * 되돌릴 방법이 없다. 알림함에 없는 고지가 푸시로만 도착한 상태는 고지 기록과 어긋난다.
 */
@Slf4j
@Component
public class NotificationPushDispatcher {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPushSender pushSender;
    private final NotificationPushDispatcher self;

    public NotificationPushDispatcher(NotificationDeliveryRepository deliveryRepository,
                                      NotificationRepository notificationRepository,
                                      NotificationPushSender pushSender,
                                      @Lazy NotificationPushDispatcher self) {
        this.deliveryRepository = deliveryRepository;
        this.notificationRepository = notificationRepository;
        this.pushSender = pushSender;
        this.self = self;
    }

    /** 트랜잭션이 커밋되면 이 발송 건을 한 번 밀어 준다. 트랜잭션이 없으면 즉시 보낸다. */
    public void sendAfterCommit(UUID deliveryId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeSend(deliveryId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeSend(deliveryId);
            }
        });
    }

    private void safeSend(UUID deliveryId) {
        try {
            self.send(deliveryId);
        } catch (Exception e) {
            // 보정 배치가 다시 집으므로 여기서는 삼킨다.
            log.warn("즉시 푸시 실패 — 보정 배치가 다시 집는다. deliveryId={}: {}", deliveryId, e.toString());
        }
    }

    /**
     * 한 건 발송. <b>이미 처리된 건은 건너뛴다</b> — 보정 배치와 동시에 돌아도 두 번 나가지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void send(UUID deliveryId) {
        NotificationDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null || !delivery.isPending()) return;

        Instant now = Instant.now();
        Notification notification = notificationRepository
                .findById(delivery.getNotificationId()).orElse(null);
        if (notification == null) {          // 적재 행이 정리됐다 — 보낼 대상이 없다
            delivery.markFailed(now, "NOTIFICATION_GONE");
            return;
        }
        pushSender.send(notification, delivery, now);
    }
}
