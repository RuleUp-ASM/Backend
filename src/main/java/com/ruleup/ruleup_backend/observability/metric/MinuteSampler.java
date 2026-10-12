package com.ruleup.ruleup_backend.observability.metric;

/** 순간값을 30초마다 버퍼에 넣는 쪽 — {@link MinuteMetricPublisher} 의 틱이 부른다. */
public interface MinuteSampler {
    void sample();
}
