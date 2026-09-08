package com.ruleup.ruleup_backend.notification.queue;

import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.domain.NotificationTab;
import com.ruleup.ruleup_backend.notification.domain.NotificationToggleGroup;

import java.util.UUID;

/**
 * 큐에 실리는 알림 1건 — <b>렌더링 결과를 다 담는다</b>.
 *
 * <p>컨슈머가 {@code notifications} 를 다시 읽지 않게 하려는 것이다. 08:00 에 8만 건이 한꺼번에
 * 소진되는데 건당 재조회를 하면 RDS 가 그대로 밀린다. 100건 묶음이 약 40KB 라 SQS 의 256KB
 * 한도에 여유가 있다.
 *
 * @param suppressKey 억제 판정용. 6개 타입만 값이 있고 나머지는 null 이다 —
 *                    null 이면 컨슈머가 억제 조회 대상에서 아예 뺀다.
 */
public record NotificationMessage(
        UUID id,
        UUID userId,
        String type,
        NotificationToggleGroup toggleGroup,
        UUID challengeId,
        NotificationTab tab,
        String title,
        String body,
        String deeplink,
        String suppressKey) {

    public static NotificationMessage from(Notification n) {
        return new NotificationMessage(n.getId(), n.getUserId(), n.getType(),
                n.toggleGroupEnum(), n.getChallengeId(), n.tabEnum(),
                n.getTitle(), n.getBody(), n.getDeeplink(), n.getSuppressKey());
    }
}
