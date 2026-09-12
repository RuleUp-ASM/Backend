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
 * @param targetToken <b>이 토큰으로만</b> 보내라는 지정. 거의 항상 null 이고, 기기 로그아웃 고지만
 *                    값을 갖는다 — 그 고지는 <b>방금 내려간 그 기기</b>에 닿아야 하는데, 유저의
 *                    활성 토큰을 조회하면 이미 새 기기의 토큰이거나 아무것도 없다.
 *                    <b>적재하지 않는다</b>: 전달 힌트일 뿐 고지의 내용이 아니라서, 알림함에 남을
 *                    이유가 없고 6개월 보관 대상도 아니다.
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
        String suppressKey,
        String targetToken) {

    /** 대상 지정이 없는 보통 알림 — 23종 중 22종이 이쪽이다. */
    public NotificationMessage(UUID id, UUID userId, String type,
                               NotificationToggleGroup toggleGroup, UUID challengeId,
                               NotificationTab tab, String title, String body, String deeplink,
                               String suppressKey) {
        this(id, userId, type, toggleGroup, challengeId, tab, title, body, deeplink,
                suppressKey, null);
    }

    public static NotificationMessage from(Notification n) {
        return from(n, null);
    }

    public static NotificationMessage from(Notification n, String targetToken) {
        return new NotificationMessage(n.getId(), n.getUserId(), n.getType(),
                n.toggleGroupEnum(), n.getChallengeId(), n.tabEnum(),
                n.getTitle(), n.getBody(), n.getDeeplink(), n.getSuppressKey(), targetToken);
    }
}
