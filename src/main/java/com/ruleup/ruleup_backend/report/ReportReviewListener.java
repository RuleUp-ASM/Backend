package com.ruleup.ruleup_backend.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportReviewListener {
    private final ReportReviewService service;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void review(ReportSubmitted event) {
        try { service.review(event.reportId()); }
        catch (Exception e) { log.warn("신고 비동기 검수 실패 reportId={}: {}", event.reportId(), e.getMessage()); }
    }
}
