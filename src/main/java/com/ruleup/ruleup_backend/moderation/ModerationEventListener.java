package com.ruleup.ruleup_backend.moderation;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 검수 이벤트 처리기.
 * - AFTER_COMMIT: 가입/변경이 DB에 확정된 뒤에만 검수한다(롤백 시 검수 안 함).
 * - @Async: 외부 LLM 호출을 응답 스레드 밖에서 → 사용자 응답이 느려지지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ModerationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ModerationEventListener.class);

    private final UserModerationService moderationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModerationRequested(UserModerationRequested event) {
        try {
            moderationService.moderate(event.userId());
        } catch (Exception e) {
            // 검수 실패가 사용자 경험을 깨면 안 된다. 로깅만 하고 PENDING 유지(다음 변경/재시도 때 다시 검수).
            log.warn("사용자 검수 처리 실패 userId={}: {}", event.userId(), e.getMessage());
        }
    }
}
