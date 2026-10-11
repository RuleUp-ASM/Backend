package com.ruleup.ruleup_backend.observability.metric;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 버퍼는 모든 프로필에 뜬다 — 계측 코드가 프로필을 몰라도 되게. 내보내기는 {@link MinuteMetricPublisher} 만 prod·stg 다. */
@Configuration
public class MetricBufferConfig {

    @Bean
    public MinuteMetricBuffer minuteMetricBuffer() {
        return new MinuteMetricBuffer(Clock.systemUTC());
    }
}
