package com.ruleup.ruleup_backend.challenge.guide;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 생성·PATCH 커밋 뒤 비동기로 안내를 만든다. 응답은 LLM 을 기다리지 않는다. 유실분은 보정 스캔이 줍는다. */
@Component
@RequiredArgsConstructor
public class VerificationGuideEventListener {
    private static final Logger log = LoggerFactory.getLogger(VerificationGuideEventListener.class);
    private final VerificationGuideService service;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(VerificationGuideRequested event) {
        try {
            service.generate(event.challengeId());
        } catch (RuntimeException failure) {
            log.warn("verification_guide_failed challengeId={} error={}",
                    event.challengeId(), failure.getClass().getSimpleName());
        }
    }
}
