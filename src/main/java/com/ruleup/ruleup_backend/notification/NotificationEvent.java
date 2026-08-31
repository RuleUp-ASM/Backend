package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationType;

import java.util.UUID;

/**
 * 도메인이 발행하는 알림 이벤트.
 *
 * <p>도메인은 "무엇이 일어났는지"만 알려주고, <b>언제 어떻게 전달할지는 이 모듈이 정한다</b>.
 *
 * @param actorId 알림을 유발한 주체. 수신자가 이 사람을 차단했으면 <b>알림 자체를 만들지 않는다</b>.
 *                시스템이 유발한 알림은 null 이다.
 * @param targetKey 중복 제어와 딥링크의 대상 식별자. 챌린지 알림이면 challengeId 다.
 * @param challengeId 챌린지별 음소거 판정용. targetKey 와 같은 값이어도 의미가 달라 따로 받는다 —
 *                    감시자 통지는 targetKey 가 통지 ID 이고 음소거 대상 방은 별개다.
 * @param deeplinkOverride 레지스트리 기본 딥링크를 대체한다. <b>대상 종류에 따라 진입점이 갈리는
 *                    소수 타입만 쓴다</b> — 모더레이션 거부는 닉네임·사진이면 프로필 편집으로,
 *                    챌린지 제목·설명이면 그 방 수정 화면으로 가야 해서 하나로 고정할 수 없다.
 *                    나머지 타입은 null 로 두고 레지스트리 값을 그대로 쓴다.
 */
public record NotificationEvent(
        UUID userId,
        NotificationType type,
        String title,
        String body,
        String targetKey,
        UUID challengeId,
        UUID actorId,
        String deeplinkOverride) {

    public static NotificationEvent of(UUID userId, NotificationType type, String title, String body) {
        return new NotificationEvent(userId, type, title, body, null, null, null, null);
    }

    public static NotificationEvent forChallenge(UUID userId, NotificationType type, String title,
                                                 String body, UUID challengeId) {
        return new NotificationEvent(userId, type, title, body,
                challengeId == null ? null : challengeId.toString(), challengeId, null, null);
    }

    /** 알림을 유발한 주체를 붙인다 — 수신자가 차단했으면 알림 자체가 만들어지지 않는다. */
    public NotificationEvent withActor(UUID actorId) {
        return new NotificationEvent(userId, type, title, body, targetKey, challengeId,
                actorId, deeplinkOverride);
    }

    /** 대상 식별자와 중복 제어 키를 붙인다. */
    public NotificationEvent withTarget(String targetKey) {
        return new NotificationEvent(userId, type, title, body, targetKey, challengeId,
                actorId, deeplinkOverride);
    }

    public NotificationEvent withDeeplink(String deeplink) {
        return new NotificationEvent(userId, type, title, body, targetKey, challengeId,
                actorId, deeplink);
    }

    /** 실제 진입 경로 — 재정의가 없으면 레지스트리 값을 쓴다. */
    public String resolvedDeeplink() {
        return (deeplinkOverride != null) ? deeplinkOverride : type.deeplink(targetKey);
    }
}
