package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.common.outbox.OutboxDispatcher;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.notification.domain.*;
import com.ruleup.ruleup_backend.report.BlockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 알림 발행 — <b>적재 → 분기 → 발송</b> 3단계의 앞 두 단계.
 *
 * <h4>순서가 계약이다</h4>
 * 적재가 모든 필터보다 먼저다. 토글·중복·야간은 <b>푸시만 막을 뿐 적재를 막지 않는다</b>.
 * 유일하게 적재 자체를 막는 것은 차단이다 — 그것도 마스킹한 알림을 대신 보내는 게 아니라
 * 아예 만들지 않는다.
 *
 * <h4>왜 아웃박스인가</h4>
 * 적재를 도메인 트랜잭션 안에 넣으면 알림 실패가 제재를 롤백시킨다. 그렇다고 {@code REQUIRES_NEW}
 * 로 먼저 커밋해 버리면 반대 방향이 깨진다 — <b>제재가 롤백됐는데 고지만 남는다</b>. 두 방향을
 * 동시에 막는 방법은 하나뿐이다: 도메인 커밋과 같은 트랜잭션에 <b>발행 의사만</b> 적어 두고
 * ({@code publish}), 실제 적재·발송은 커밋 이후 디스패처가 한다({@code deliver}).
 *
 * <p>{@code deliver} 안에서 푸시를 직접 보내지 않는 것도 같은 이유다. FCM 호출이 적재 트랜잭션
 * 안에 있으면 <b>커밋되지 않은 알림의 푸시가 먼저 나가는</b> 창이 생긴다. 적재 행을 남기고
 * {@code notification_deliveries.sent_at} 이 채워지길 기다리는 쪽으로 넘긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPublisher {

    /** 아웃박스 라우팅 키. 핸들러와 이 값 하나로 묶인다. */
    public static final String OUTBOX_TYPE = "NOTIFICATION";

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationSettingRepository settingRepository;
    private final NotificationMuteRepository muteRepository;
    private final NotificationDedupRepository dedupRepository;
    private final BlockService blockService;
    private final OutboxService outboxService;
    private final OutboxDispatcher outboxDispatcher;
    private final NotificationPushDispatcher pushDispatcher;

    /**
     * 발행 진입점 — <b>도메인 트랜잭션에 합류해 발행 의사만 적는다.</b> 알림함 적재도 푸시도
     * 여기서는 일어나지 않는다. 도메인이 롤백되면 이 행도 함께 사라진다.
     */
    public void publish(NotificationEvent event) {
        outboxService.enqueue(OUTBOX_TYPE, event, null);
        // 커밋 직후 한 번 흘려 달라는 신호. 보장은 스윕이 하고 이건 지연만 줄인다.
        outboxDispatcher.requestFlush();
    }

    /**
     * 실제 적재·분기 — 아웃박스 디스패처가 <b>도메인 커밋 이후에</b> 부른다.
     * 반환값은 적재된 알림이며, 차단으로 생성하지 않았으면 empty 다.
     */
    @Transactional
    public Optional<Notification> deliver(NotificationEvent event) {
        Instant now = Instant.now();

        // ① 차단 필터는 생성 단계다. 적재 후 가리는 방식은 쓰지 않는다 — 알림함에 남아 있으면
        //    "차단했는데 알림이 온다"는 인지가 그대로 발생한다.
        if (isBlocked(event)) {
            log.debug("차단 관계라 알림을 생성하지 않는다. type={} user={}", event.type(), event.userId());
            return Optional.empty();
        }

        // ② 적재 먼저 커밋 — 이 시점에 고지가 성립한다.
        Notification notification = notificationRepository.save(Notification.of(
                event.userId(), event.type(), event.title(), event.body(), event.targetKey(),
                event.resolvedDeeplink(), now));

        // ③ 분기 — 여기부터는 푸시 얘기이므로 실패해도 고지는 유효하다.
        NotificationDelivery delivery = deliveryRepository.save(decide(event, notification, now));
        // 보낼 대상이면 커밋 직후 밀어 준다. 보류·생략분은 sentAt 이 이미 차 있어 대상이 아니다.
        if (delivery.isPending()) pushDispatcher.sendAfterCommit(delivery.getId());
        return Optional.of(notification);
    }

    private boolean isBlocked(NotificationEvent event) {
        return event.actorId() != null
                && blockService.isUserBlocked(event.userId(), event.actorId());
    }

    /**
     * 분류별 발송 판정. 분기 순서가 계약이다 —
     * A는 즉시, B는 토글 → 음소거 → 중복 → 야간, C는 동의 → 발송 창.
     */
    private NotificationDelivery decide(NotificationEvent event, Notification notification, Instant now) {
        NotificationType type = event.type();

        return switch (type.category()) {
            // 시각 무관 즉시 발송. 야간 보류의 유일한 예외이며 중복 제어도 적용하지 않는다.
            case A -> sendNow(notification, now);

            case B -> {
                if (!isEnabled(event)) yield suppress(notification, now,
                        NotificationDelivery.SuppressedReason.TOGGLE_OFF);
                if (isMuted(event)) yield suppress(notification, now,
                        NotificationDelivery.SuppressedReason.MUTED);
                if (!claimDedup(event, now)) yield suppress(notification, now,
                        NotificationDelivery.SuppressedReason.DEDUP);
                // 야간이면 푸시만 미룬다. 알림함에는 이미 적재돼 있다.
                yield NotificationWindow.isNight(now)
                        ? NotificationDelivery.scheduled(notification.getId(),
                                NotificationWindow.nextMorning(now))
                        : sendNow(notification, now);
            }

            case C -> {
                if (!isEnabled(event)) yield suppress(notification, now,
                        NotificationDelivery.SuppressedReason.TOGGLE_OFF);
                // 야간 광고는 미루지 않고 보내지 않는다 — 큐에 쌓아 두면 경계 계산이
                // 틀렸을 때 그대로 정보통신망법 위반이 된다.
                if (!NotificationWindow.isMarketingAllowed(now)) yield suppress(notification, now,
                        NotificationDelivery.SuppressedReason.NIGHT_MARKETING);
                if (!claimDedup(event, now)) yield suppress(notification, now,
                        NotificationDelivery.SuppressedReason.DEDUP);
                yield sendNow(notification, now);
            }
        };
    }

    /**
     * 지금 보낼 대상으로 <b>예약</b>한다 — 여기서 FCM 을 부르지 않는다.
     *
     * <p>{@code sentAt} 이 null 인 채로 커밋되고, 커밋 직후 {@link NotificationPushDispatcher} 가
     * 즉시 집어 보낸다. 그 콜백이 유실돼도 {@link NotificationBatch} 의 보정 배치가 같은 행을
     * 다시 집으므로 <b>고지가 사라지지 않는다</b>.
     */
    private NotificationDelivery sendNow(Notification notification, Instant now) {
        return NotificationDelivery.scheduled(notification.getId(), now);
    }

    private NotificationDelivery suppress(Notification notification, Instant now,
                                          NotificationDelivery.SuppressedReason reason) {
        return NotificationDelivery.suppressed(notification.getId(), now, reason);
    }

    /** 행이 없으면 기본 ON — 신규 타입 추가 시 전원 백필이 필요 없다. */
    private boolean isEnabled(NotificationEvent event) {
        return settingRepository
                .findById(new NotificationSetting.Key(event.userId(), event.type().name()))
                .map(NotificationSetting::isEnabled)
                .orElse(true);
    }

    /** 유형별 토글과 <b>AND</b> 로 결합한다. 챌린지 컨텍스트가 없는 타입은 음소거 대상이 아니다. */
    private boolean isMuted(NotificationEvent event) {
        if (!event.type().isMuteable() || event.challengeId() == null) return false;
        return muteRepository
                .findById(new NotificationMute.Key(event.userId(), event.challengeId()))
                .isPresent();
    }

    /**
     * 중복 제어 — 윈도우 밖일 때만 갱신하고, <b>갱신에 성공한 요청만</b> 발송한다.
     * 행을 잠그고 읽어 경합에서도 두 번 나가지 않게 한다.
     */
    private boolean claimDedup(NotificationEvent event, Instant now) {
        var window = event.type().dedupWindow();
        if (window == null) return true;                       // 필수(A) — 적용하지 않는다

        String targetKey = NotificationDedup.normalize(event.targetKey());
        var existing = dedupRepository.findWithLockByUserIdAndTypeAndTargetKey(
                event.userId(), event.type().name(), targetKey);

        if (existing.isEmpty()) {
            dedupRepository.save(NotificationDedup.of(
                    event.userId(), event.type().name(), targetKey, now));
            return true;
        }
        NotificationDedup dedup = existing.get();
        if (dedup.getLastSentAt().plus(window).isAfter(now)) return false;   // 윈도우 안 — 생략
        dedup.touch(now);
        return true;
    }
}
