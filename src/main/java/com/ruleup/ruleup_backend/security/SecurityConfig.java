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
    private final com.ruleup.ruleup_backend.user.UserRepository userRepository;
    private final com.ruleup.ruleup_backend.sanction.SanctionService sanctionService;

    // 로그인 없이 접근 가능한 공개 경로
    private static final String[] PUBLIC = {
            "/api/v1/intro",                 // 앱 인트로/버전 게이트 (로그인 전 스플래시에서 호출)
            "/api/v1/auth/oauth/**",         // 4.1 / 4.2 소셜 로그인 (+ /callback)
            "/api/v1/auth/signup",           // 4.3 가입
            "/api/v1/auth/refresh",          // 4.4 토큰 재발급 (refreshToken 사용)
            "/api/v1/nicknames/**",          // 4.6 닉네임 검사
            "/api/v1/categories",            // 4.7 카테고리 마스터
            "/api/v1/challenge-categories",  // 탐색 §2.2 홈 카테고리 그리드(공개 표시용 수치)
            "/api/v1/app-links/check",       // 딥링크 진입 시점 — 아직 로그인 전일 수 있다
            "/api/v1/dev/tokens",            // 개발용 토큰 발급(비-prod 전용). 시크릿 헤더로 따로 막는다
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
        // 계정 상태 게이트 — status 를 먼저 보고 SUSPENDED 일 때만 sanctions 로 차단 범위를 정한다.
        // (@Component 로 두면 Boot 가 서블릿 필터로도 자동 등록해 이중 실행되므로 여기서 직접 만든다)
        AccountStatusFilter accountStatusFilter = new AccountStatusFilter(userRepository, sanctionService);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC).permitAll()
                        .requestMatchers(SWAGGER).permitAll()
                        // 감시자: 초대 진입(GET)은 로그인 선택 — 링크를 받은 사람이 아직 회원이 아닐 수 있다.
                        // 수락(POST)은 관계를 만드는 행위라 인증이 필요하다(공개 목록에서 제외).
                        // 구 OTP·비유저 동의·수신거부 경로는 감시자 도메인 개편(2026-08-28)으로 사라졌다.
                        .requestMatchers(HttpMethod.GET,  "/api/v1/watchers/invitations/*").permitAll()
                        // 4.5 로그아웃은 명세상 "로그인 O" → 인증 필요(공개 목록에서 제외)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(accountStatusFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}