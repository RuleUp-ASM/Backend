package com.ruleup.ruleup_backend.observability.metric;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 분 경계를 시험이 직접 넘기는 시계. */
public final class TestClock extends Clock {

    private Instant now;

    public TestClock(Instant start) {
        this.now = start;
    }

    public void advanceSeconds(long seconds) {
        now = now.plusSeconds(seconds);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override public Clock withZone(ZoneId zone) { return this; }

    @Override public Instant instant() { return now; }
}
