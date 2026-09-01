package com.ruleup.ruleup_backend.common.outbox;

/**
 * 아웃박스 한 종류를 실제로 발행하는 쪽.
 *
 * <p>구현체는 <b>멱등해야 한다.</b> 디스패처는 "발행했지만 처리 표시를 남기기 전에 죽는" 창을
 * 없앨 수 없으므로(2PC 를 쓰지 않는 한) 같은 메시지가 두 번 올 수 있다. 알림은 dedup 이,
 * 자동 탈퇴는 "이미 나간 방은 건너뛴다"가 그 역할을 한다.
 */
public interface OutboxHandler {

    /** 이 핸들러가 맡는 {@code outbox_messages.type}. */
    String type();

    /**
     * 발행. 예외를 던지면 디스패처가 실패로 기록하고 백오프 뒤 다시 부른다.
     *
     * @param payload 커밋 당시의 스냅샷 JSON
     */
    void handle(String payload);
}
