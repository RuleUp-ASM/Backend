package com.ruleup.ruleup_backend.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 시큐리티 설정 (Stateless JWT).
 * 세션/CSRF/formLogin/httpBasic 끔. 공개 경로 외엔 전부 토큰 필요.
 * 경로는 API 명세(/api/v1/...)에 맞췄고, Swagger 문서 경로도 공개로 열어둔다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final JwtAuthenticationEntryPoint entryPoint;

    // 로그인 없이 접근 가능한 공개 경로
    private static final String[] PUBLIC = {
            "/intro",                        // 앱 인트로/버전 게이트 (로그인 전 스플래시에서 호출)
            "/api/v1/account/login/**",      // 4.1 / 4.2 소셜 로그인
            "/api/v1/account/signup",        // 4.3 가입
            "/api/v1/account/token/refresh", // 4.4 토큰 재발급 (refreshToken 사용)
            "/api/v1/nicknames/**",          // 4.6 닉네임 검사
            "/api/v1/categories",            // 4.7 카테고리 마스터
            "/files/**",                     // 정적 이미지 서빙
            "/actuator/health"
    };

    // Swagger / OpenAPI 문서 경로
    private static final String[] SWAGGER = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-resources/**"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtProvider);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC).permitAll()
                        .requestMatchers(SWAGGER).permitAll()
                        // 4.5 로그아웃은 명세상 "로그인 O" → 인증 필요(공개 목록에서 제외)
                        .requestMatchers(HttpMethod.POST, "/api/v1/account/logout").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}