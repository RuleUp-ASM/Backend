package com.ruleup.ruleup_backend.observability.usage;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 화면 조회 기록 인터셉터 — 화면 API 경로에만 건다(나머지 요청은 패턴 비교조차 하지 않는다). */
@Configuration
public class ScreenViewWebConfig implements WebMvcConfigurer {

    private final ScreenViewInterceptor interceptor;

    public ScreenViewWebConfig(ScreenViewInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(
                "/api/v1/me/home", "/api/v1/me/stats", "/api/v1/me/calendar", "/api/v1/me/calendar/*",
                "/api/v1/me/tier", "/api/v1/me/tier/*", "/api/v1/rankings/challenges",
                "/api/v1/challenges/*/room", "/api/v1/challenges/*/threads", "/api/v1/challenges/*/ranking",
                "/api/v1/challenges/*/calendar", "/api/v1/challenges/*/members");
    }
}
