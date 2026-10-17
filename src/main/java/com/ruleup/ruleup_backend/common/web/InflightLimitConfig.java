package com.ruleup.ruleup_backend.common.web;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** 동시 처리 상한. {@code app.http.max-inflight} 가 0(기본)이면 켜지지 않는다. */
@Configuration
@ConditionalOnExpression("${app.http.max-inflight:0} > 0")
public class InflightLimitConfig {

    @Bean
    public FilterRegistrationBean<InflightLimitFilter> inflightLimitFilter(
            @Value("${app.http.max-inflight}") int maxInflight,
            @Value("${app.http.inflight-wait-ms:1000}") long waitMillis,
            MeterRegistry registry) {
        FilterRegistrationBean<InflightLimitFilter> reg =
                new FilterRegistrationBean<>(new InflightLimitFilter(maxInflight, waitMillis, registry));
        // 요청 id·요청 로그(+1·+2) 뒤라 거절도 같은 id 로 남고, 시큐리티(-100)보다 앞이라 거절할 요청에
        // 토큰 검증·계정 상태 조회(DB)를 쓰지 않는다.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        reg.addUrlPatterns("/api/*");
        return reg;
    }
}
