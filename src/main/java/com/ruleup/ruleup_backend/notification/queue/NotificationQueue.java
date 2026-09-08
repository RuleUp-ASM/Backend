package com.ruleup.ruleup_backend.notification.queue;

import java.util.List;

/**
 * 푸시 대기열 — 적재와 발송을 가르는 경계다.
 *
 * <p>발행측은 <b>커밋 이후에</b> 여기로 넘기고 끝낸다. 실패하면 그 알림은 푸시가 나가지 않지만
 * <b>적재는 이미 끝났으므로 절대 규칙 1 은 지켜지고</b>, 공통 3절이 푸시 유실을 허용한다.
 * 그래서 이 호출의 예외를 도메인 쪽으로 올리지 않는다.
 *
 * <p>구현은 SQS 표준 큐다. 인터페이스로 끊어 둔 이유는 하나다 — 적재 계약을 검증하는 테스트가
 * 큐를 띄우지 않고도 「무엇이 큐에 들어갔는가」를 볼 수 있어야 한다.
 */
public interface NotificationQueue {

    /** 묶음 투입. 100건 단위로 나눠 부르는 것은 호출측 책임이다. */
    void enqueue(List<NotificationMessage> messages);
}
