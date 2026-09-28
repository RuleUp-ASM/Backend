package com.ruleup.ruleup_backend.verification.web;

import com.ruleup.ruleup_backend.verification.service.VerificationMetrics;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SyncFailureMetricsConfig {

    @Bean
    public FilterRegistrationBean<SyncFailureMetricsFilter> syncFailureMetricsFilter(VerificationMetrics metrics) {
        FilterRegistrationBean<SyncFailureMetricsFilter> reg =
                new FilterRegistrationBean<>(new SyncFailureMetricsFilter(metrics));
        reg.addUrlPatterns("/api/v1/verifications/sync");
        return reg;
    }
}
