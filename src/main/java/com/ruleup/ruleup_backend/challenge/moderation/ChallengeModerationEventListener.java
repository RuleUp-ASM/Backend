package com.ruleup.ruleup_backend.challenge.moderation;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 챌린지 이미지 검수 이벤트 처리기 (CLAUDE.md §5.1 / §9 비동기).
 * - AFTER_COMMIT: 생성/이미지변경이 DB에 확정된 뒤에만 검수(롤백 시 검수 안 함).
 * - @Async: SafeSearch 호출을 응답 스레드 밖에서 → 생성/수정 응답이 느려지지 않는다.
 *
 * (인프라가 갖춰지면 이 인프로세스 리스너를 SQS consumer 로 치환 — §9. 계약·동작은 동일.)
 */
@Component
@RequiredArgsConstructor
public class ChallengeModerationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ChallengeModerationEventListener.class);

    private final ChallengeModerationService moderationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModerationRequested(ChallengeModerationRequested event) {
        try {
            moderationService.moderate(event.challengeId());
        } catch (Exception e) {
            // 검수 실패가 생성/수정 경험을 깨면 안 된다. 로깅만 하고 PENDING_REVIEW 유지(다음 변경 때 재검수).
            log.warn("챌린지 이미지 검수 처리 실패 challengeId={}: {}", event.challengeId(), e.getMessage());
        }
    }
}
