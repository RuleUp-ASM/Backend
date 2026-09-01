package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.domain.NotificationDelivery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 알림 배치 — 아침 요약 발송, 미발송 보정, 보관 기간 정리.
 *
 * <p>실행 창은 <b>02:00~03:00 점검 창과 00시 인증 판정 배치를 피한다</b>.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationBatch {

    /** 한 번에 처리할 상한 — 08:00 에 야간 보류분이 한꺼번에 터지므로 나눠 흘린다. */
    private static final int BATCH_SIZE = 500;

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPushSender pushSender;

    /**
     * 아침 요약 — 야간에 쌓인 기능(B) 푸시를 08:00 에 내보낸다.
     *
     * <p><b>N건을 개별 푸시로 보낸다.</b> 알림함은 어차피 개별 적재이고, 묶음 요약 한 건으로 보내면
     * 어느 알림이 왔는지 잠금화면에서 알 수 없어 진입률이 떨어진다.
     *
     * <p>유저 단위로 묶어 처리하는 것은 발송 순서를 안정시키기 위함이다 — 같은 사람의 알림이
     * 뒤섞여 도착하면 시간순이 깨져 보인다.
     *
     * <p>{@code sentAt} 이 채워진 건은 건너뛰므로 <b>멱등</b>하다. 배치가 두 번 돌아도 중복 발송되지 않는다.
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Seoul")
    @Transactional
    public int flushMorningDigest() {
        return flushDue(Instant.now(), "morning-digest");
    }

    /**
     * 미발송 보정 — 예정 시각이 지났는데 아직 안 나간 건을 재시도한다.
     * 상시 0에 가깝게 유지돼야 하며, 값이 쌓이면 큐나 배치가 죽은 것이다.
     *
     * <p><b>이 배치가 푸시 유실을 막는 쪽이다.</b> 정상 경로는 적재 커밋 직후의 즉시 발송
     * ({@link NotificationPushDispatcher})이지만 그건 JVM 콜백이라 서버가 죽으면 사라진다.
     * 주기를 시간 단위에서 5분으로 좁힌 이유가 이것이다 — 필수(A) 고지가 최대 한 시간 늦게
     * 도착하는 것은 "즉시 발송"이라는 분류의 약속과 어긋난다.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    @Transactional
    public int reconcilePending() {
        return flushDue(Instant.now(), "reconcile");
    }

    private int flushDue(Instant now, String tag) {
        List<NotificationDelivery> due = deliveryRepository.findDue(now, Limit.of(BATCH_SIZE));
        if (due.isEmpty()) return 0;

        Map<java.util.UUID, Notification> notifications = notificationRepository
                .findAllById(due.stream().map(NotificationDelivery::getNotificationId).toList())
                .stream().collect(Collectors.toMap(Notification::getId, n -> n));

        // 유저 단위로 묶어 같은 사람의 알림이 시간순으로 나가게 한다.
        int sent = 0;
        for (NotificationDelivery delivery : due.stream()
                .sorted((a, b) -> {
                    Notification na = notifications.get(a.getNotificationId());
                    Notification nb = notifications.get(b.getNotificationId());
                    if (na == null || nb == null) return 0;
                    int byUser = na.getUserId().compareTo(nb.getUserId());
                    return byUser != 0 ? byUser : na.getCreatedAt().compareTo(nb.getCreatedAt());
                }).toList()) {

            Notification notification = notifications.get(delivery.getNotificationId());
            if (notification == null) {          // 적재 행이 정리됐다 — 보낼 대상이 없다
                delivery.markFailed(now, "NOTIFICATION_GONE");
                continue;
            }
            pushSender.send(notification, delivery, now);
            sent++;
        }
        log.info("{} — 발송 시도 {}건", tag, sent);
        return sent;
    }

    /**
     * 보관 기간 경과분 정리. <b>하드 삭제</b>다 — 고지 성립은 보관 기간 안에서만 다투므로
     * 6개월이 지난 행을 남길 이유가 없고, 남기면 알림함 인덱스만 무겁게 만든다.
     */
    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public int purgeExpired() {
        Instant threshold = Instant.now().minus(NotificationService.RETENTION);
        List<Notification> expired = notificationRepository
                .findByCreatedAtBefore(threshold, Limit.of(BATCH_SIZE));
        if (expired.isEmpty()) return 0;

        // 발송 기록이 FK 로 매달려 있으므로 먼저 지운다.
        expired.forEach(n -> deliveryRepository.deleteAll(
                deliveryRepository.findByNotificationId(n.getId())));
        notificationRepository.deleteAll(expired);
        log.info("알림함 보관 기간 경과분 정리 — {}건", expired.size());
        return expired.size();
    }
}
