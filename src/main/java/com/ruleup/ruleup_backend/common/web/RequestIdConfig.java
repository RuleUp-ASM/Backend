package com.ruleup.ruleup_backend.common.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class RequestIdConfig {

    /**
     * 인코딩 필터(HIGHEST_PRECEDENCE) 바로 뒤, 요청 로그 필터보다 앞 — 요청 로그 줄에도 같은 ID 가 찍혀야 한다.
     */
    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
