package com.ruleup.ruleup_backend.verification.evaluator;

import java.time.Instant;

/** 인증 평가 창 [start, end]. */
public record Window(Instant start, Instant end) {
    public boolean contains(Instant t) {
        return t != null && !t.isBefore(start) && !t.isAfter(end);
    }
    /** now 기준 창이 닫혔는지. */
    public boolean isClosed(Instant now) {
        return now.isAfter(end);
    }
}
