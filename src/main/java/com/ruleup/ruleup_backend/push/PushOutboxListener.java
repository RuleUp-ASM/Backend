package com.ruleup.ruleup_backend.push;

import com.ruleup.ruleup_backend.common.event.PermissionGapDetected;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 실시간 권한공백 감지 → <b>고스트 푸시 큐 적재 + 알림함 적재</b>(§8.5).
 * 동기 @EventListener — sync 평가 트랜잭션 안에서 둘 다 원자적으로 일어난다(DB만 건드림).
 * 실제 발송은 별도 스윕({@link PushOutboxDispatcher}).
 *
 * <p><b>무음 푸시만으로는 기록이 남지 않는다.</b> 고스트 푸시는 앱을 깨우기만 하므로 앱을 열지
 * 않은 사용자에게는 아무 흔적도 없고, 그 사이 자동 인증은 계속 skip 되다가 2사이클 미해소로
 * 강퇴된다. 셋업을 마친 뒤 OS 에서 권한을 끈 사용자는 {@code PENDING_SETUP} 배치에도 잡히지
 * 않으므로, <b>이 경로가 그 사람의 유일한 고지 지점</b>이다(절대 규칙 1 — 모든 알림은 예외 없이
 * 알림 센터에 적재된다).
 *
 * <p><b>두 적재를 각자의 try 로 감싼다.</b> 한쪽이 실패했다고 다른 쪽을 건너뛰면 안 된다.
 * 예외를 삼키는 것은 위쪽 계약을 그대로 따른 것이다 — 여기서 예외를 올리면 알림 한 건 때문에
 * <b>인증 sync 평가 전체가 롤백</b>된다. 적재 실패는 경고 로그로 드러낸다.
 */
@Component
@RequiredArgsConstructor
public class PushOutboxListener {

    private static final Logger log = LoggerFactory.getLogger(PushOutboxListener.class);

    private final PushOutboxService pushOutboxService;
    private final NotificationPublisher notificationPublisher;

    @EventListener
    public void onPermissionGap(PermissionGapDetected event) {
        try {
            pushOutboxService.enqueuePermissionGap(
                    event.userId(), event.challengeId(), event.targetDate(),
                    event.signalType(), event.detectedAt());
        } catch (Exception e) {
            log.warn("고스트 푸시 적재 실패 userId={} challengeId={}: {}",
                    event.userId(), event.challengeId(), e.getMessage());
        }

        try {
            notifyRegrantRequired(event);
        } catch (Exception e) {
            log.warn("권한 재허용 고지 적재 실패 userId={} challengeId={}: {}",
                    event.userId(), event.challengeId(), e.getMessage());
        }
    }

    /**
     * 멱등 키에 <b>귀속 날짜</b>를 넣는다. 이벤트 자체가 {@code targetDate} 를 「하루 1건 멱등 키」로
     * 규정하고 있고, 날짜를 빼면 키가 고정값이 되어 {@code uq_notifications_dedup} 에 걸려
     * <b>평생 한 번만</b> 적재된다 — 권한이 계속 막혀 있어도 다음 날부터는 아무 고지가 없다.
     *
     * <p>푸시 빈도는 이 키가 아니라 레지스트리의 24시간 억제 키 {@code (permission, challenge_id)}
     * 가 제어한다. 적재와 발송의 층이 다르다.
     */
    private void notifyRegrantRequired(PermissionGapDetected event) {
        String challengeId = event.challengeId().toString();
        notificationPublisher.publish(NotificationEvent.of(event.userId(),
                NotificationType.PERMISSION_REGRANT_REQUIRED,
                "인증 권한을 다시 허용해주세요",
                "권한이 없어 자동 인증이 기록되지 않고 있어요. 방 설정에서 다시 허용해주세요.",
                Map.of(NotificationParams.EVENT_KEY,
                                challengeId + ":" + event.signalType() + ":" + event.targetDate(),
                        NotificationParams.CHALLENGE_ID, challengeId,
                        NotificationParams.PERMISSION, event.signalType())));
    }
}
