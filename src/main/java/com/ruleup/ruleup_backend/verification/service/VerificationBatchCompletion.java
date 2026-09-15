package com.ruleup.ruleup_backend.verification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;

/** Completion is durable and includes deferred failures, not merely this poll's due rows. */
@Service
@RequiredArgsConstructor
public class VerificationBatchCompletion {
    private final JdbcTemplate jdbc;

    public boolean needsMaterialization(LocalDate batchOn) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM verification_batch_completions WHERE batch_on=? AND materialized_at IS NOT NULL", Integer.class,batchOn)==0;
    }

    @Transactional
    public void materialized(LocalDate batchOn) {
        jdbc.update("INSERT INTO verification_batch_completions(batch_on,materialized_at) VALUES(?,UTC_TIMESTAMP(6)) " +
                "ON DUPLICATE KEY UPDATE materialized_at=VALUES(materialized_at)", batchOn);
    }

    @Transactional
    public void finishIfDrained(LocalDate batchOn) {
        jdbc.update("UPDATE verification_batch_completions SET finalized_at=UTC_TIMESTAMP(6) WHERE batch_on=? " +
                "AND materialized_at IS NOT NULL AND finalized_at IS NULL " +
                "AND NOT EXISTS(SELECT 1 FROM VerificationDaily WHERE targetDate<=? AND status='PENDING')",
                batchOn, batchOn.minusDays(2));
    }
}
