package com.ruleup.ruleup_backend.notification.consumer;

import java.util.List;
import java.util.UUID;

/**
 * 전송 결과 1건.
 *
 * @param retryable 재시도해도 소용이 있는 오류인가({@code UNAVAILABLE}·{@code INTERNAL}·
 *                  {@code QUOTA_EXCEEDED}). 참이면 <b>SQS 메시지를 삭제하지 않아</b>
 *                  가시성 만료 후 다시 받는다.
 * @param deadTokens {@code UNREGISTERED}·{@code INVALID_ARGUMENT} 로 죽은 토큰. 비활성화한다.
 */
public record PushOutcome(UUID notificationId, boolean success, boolean retryable,
                          String errorCode, List<String> deadTokens) {

    public static PushOutcome success(UUID notificationId) {
        return new PushOutcome(notificationId, true, false, null, List.of());
    }

    public static PushOutcome failed(UUID notificationId, String errorCode,
                                     boolean retryable, List<String> deadTokens) {
        return new PushOutcome(notificationId, false, retryable, errorCode, deadTokens);
    }
}
