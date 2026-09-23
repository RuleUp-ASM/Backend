package com.ruleup.ruleup_backend.admin.web;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 백오피스 경로 전체에 접근 통제를 건다 — 엔드포인트를 추가해도 빠뜨릴 자리가 없다. */
@Configuration
@RequiredArgsConstructor
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminAccessInterceptor adminAccessInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAccessInterceptor)
                .addPathPatterns("/api/v1/admin/**")
                // 진입 로그인만 뺀다 — 토큰을 받으러 오는 경로에 토큰을 요구할 수 없다.
                // 그 경로의 거부는 AdminAuthService 가 직접 감사 로그에 남긴다.
                .excludePathPatterns("/api/v1/admin/auth/login");
    }
}
