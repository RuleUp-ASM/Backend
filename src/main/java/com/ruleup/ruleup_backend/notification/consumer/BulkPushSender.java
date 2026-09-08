package com.ruleup.ruleup_backend.notification.consumer;

import java.util.List;

/**
 * 묶음 푸시 전송 — 컨슈머가 결과를 <b>알아야</b> 하므로 기존 {@code PushSender} 와 갈라 둔다.
 *
 * <p>{@code PushSender.sendDisplay} 는 실패를 삼킨다(배치·트리거 흐름을 깨지 않으려고). 컨슈머는
 * 반대로 결과가 필요하다 — 성공해야 {@code pushed_at} 을 찍고, 재시도 가능 오류여야 SQS 메시지를
 * 남기며, 죽은 토큰이어야 기기를 비활성화한다.
 */
public interface BulkPushSender {

    /** 요청 순서와 무관하게 요청마다 결과 하나를 돌려준다. */
    List<PushOutcome> send(List<PushRequest> requests);
}
