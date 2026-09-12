package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationType;

import java.util.Map;
import java.util.UUID;

/**
 * 도메인이 발행하는 알림 이벤트.
 *
 * <p>도메인은 "무엇이 일어났는지"만 알려주고, <b>언제 어떻게 전달할지는 이 모듈이 정한다</b>.
 *
 * <p>{@code params} 가 렌더링과 키 구성의 유일한 입력이다. 엔티티를 넘기지 않는 이유는
 * 적재가 도메인 트랜잭션 안에 있기 때문이다 — 템플릿이 엔티티를 들여다보다 터지면
 * <b>알림 버그가 강퇴 판정을 롤백시킨다</b>(백엔드 4-1).
 *
 * @param challengeId <b>카운터 귀속 전용</b>. {@code params} 의 챌린지 값과 의미가 다르다 —
 *                    감시자 통지는 챌린지에서 발생하지만 수신자가 방 멤버가 아니라 여기는 null 이다.
 * @param deeplinkOverride 레지스트리 기본 딥링크를 대체한다. <b>대상 종류에 따라 진입점이 갈리는
 *                    소수 타입만 쓴다</b> — 심사 거부는 닉네임·사진이면 프로필 편집으로,
 *                    챌린지 제목·설명이면 그 방 수정 화면으로 가야 해서 하나로 고정할 수 없다.
 * @param targetToken <b>이 기기로만</b> 보내라는 지정. 기기 로그아웃 고지만 쓴다 — 그 고지는
 *                    방금 내려간 기기에 닿아야 하는데, 발송 시점에 유저의 활성 토큰을 조회하면
 *                    이미 새 기기의 것이거나 비어 있다. <b>적재되지 않는다</b>: 전달 힌트일 뿐
 *                    고지의 내용이 아니라서 알림함에 남을 이유가 없다.
 */
public record NotificationEvent(
        UUID userId,
        NotificationType type,
        String title,
        String body,
        UUID challengeId,
        Map<String, String> params,
        String deeplinkOverride,
        String targetToken) {

    public NotificationEvent {
        params = (params == null) ? Map.of() : Map.copyOf(params);
    }

    public static NotificationEvent of(UUID userId, NotificationType type, String title, String body,
                                       Map<String, String> params) {
        return new NotificationEvent(userId, type, title, body, null, params, null, null);
    }

    /**
     * 카운터가 뜰 방이 있는 알림. 감시자 통지에는 쓰지 않는다 — 수신자의 「내 챌린지」 목록에
     * 그 방이 없어 카운터가 뜰 자리가 없다.
     */
    public static NotificationEvent forChallenge(UUID userId, NotificationType type, String title,
                                                 String body, UUID challengeId,
                                                 Map<String, String> params) {
        return new NotificationEvent(userId, type, title, body, challengeId, params, null, null);
    }

    public NotificationEvent withDeeplink(String deeplink) {
        return new NotificationEvent(userId, type, title, body, challengeId, params, deeplink,
                targetToken);
    }

    public NotificationEvent withParams(Map<String, String> more) {
        java.util.Map<String, String> merged = new java.util.HashMap<>(params);
        merged.putAll(more);
        return new NotificationEvent(userId, type, title, body, challengeId, merged,
                deeplinkOverride, targetToken);
    }

    /**
     * 이 기기로만 보낸다. 기기 로그아웃 고지가 유일한 사용처이며, <b>발행 시점에 붙잡은</b>
     * 토큰이어야 한다 — 발송 시점에는 그 기기가 이미 비활성이라 조회로는 찾을 수 없다.
     */
    public NotificationEvent withTargetToken(String token) {
        return new NotificationEvent(userId, type, title, body, challengeId, params,
                deeplinkOverride, token);
    }

    /** 실제 진입 경로 — 재정의가 없으면 레지스트리 값을 쓴다. */
    public String resolvedDeeplink() {
        return (deeplinkOverride != null) ? deeplinkOverride : type.deeplink(params);
    }

    public String dedupKey() {
        return type.dedupKey(userId, params);
    }

    public String suppressKey() {
        return type.suppressKey(params);
    }
}
