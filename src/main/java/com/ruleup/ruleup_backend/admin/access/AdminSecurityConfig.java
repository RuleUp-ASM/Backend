package com.ruleup.ruleup_backend.admin.access;

import com.ruleup.ruleup_backend.user.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

/**
 * 관리자 역할({@code app.runtime.role=admin})의 보안 체인. 일반 API 의 {@code SecurityConfig} 와
 * <b>둘 중 하나만</b> 뜬다.
 *
 * <ul>
 *   <li>관리자 경로({@code /api/v1/admin/**})만 받는다. 나머지는 매핑도 없고(역할별 핸들러 매핑)
 *       여기서도 거부한다</li>
 *   <li>세션·폼 로그인·Basic 없음. 인증은 요청마다 Access JWT 로만 — {@link CloudflareAccessFilter}</li>
 *   <li>CORS 는 켜지 않는다. 콘솔은 같은 오리진(Worker 프록시)으로만 부른다 — 다른 오리진의 브라우저
 *       요청은 preflight 단계에서 막힌다. CSRF 는 Origin 검증으로 대신한다(토큰을 쓰는 화면이 아니다)</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@ConditionalOnExpression("'${app.runtime.role:api}'.equalsIgnoreCase('admin')")
public class AdminSecurityConfig {

    /** 팀 도메인의 공개키 묶음. 시험은 {@code @Primary} 로 직접 만든 키를 대신 꽂는다. */
    @Bean
    AccessKeySource accessKeySource(AdminAccessProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return () -> {
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(properties.certsUrl()))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) throw new IllegalStateException("certs status=" + res.statusCode());
                return res.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @Bean
    CloudflareAccessVerifier cloudflareAccessVerifier(AdminAccessProperties properties, AccessKeySource keySource) {
        return new CloudflareAccessVerifier(properties, keySource, Clock.systemUTC());
    }

    @Bean
    OperatorDirectory operatorDirectory(UserRepository userRepository, TransactionTemplate transactionTemplate) {
        return new OperatorDirectory(userRepository, transactionTemplate);
    }

    @Bean
    SecurityFilterChain adminFilterChain(HttpSecurity http, CloudflareAccessVerifier verifier,
                                         AdminAccessProperties properties, OperatorDirectory operators) throws Exception {
        CloudflareAccessFilter accessFilter = new CloudflareAccessFilter(verifier, properties, operators);
        AdminRequestAuditFilter auditFilter = new AdminRequestAuditFilter();

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("OPERATOR")
                        .anyRequest().denyAll())
                .addFilterBefore(auditFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(accessFilter, AdminRequestAuditFilter.class);
        return http.build();
    }
}
