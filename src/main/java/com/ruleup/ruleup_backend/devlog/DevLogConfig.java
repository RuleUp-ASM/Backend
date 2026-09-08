package com.ruleup.ruleup_backend.devlog;

import com.ruleup.ruleup_backend.security.JwtProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

/**
 * 시연용 요청 로그 등록. 테스트 프로파일에서는 뜨지 않는다(테스트 출력이 요청 로그로 덮인다).
 *
 * <p>{@code app.request-log.enabled=false} 로 끈다. 기능 자체를 지울 때는 이 패키지를 삭제하고
 * {@code application.yaml} 의 {@code app.request-log} 블록만 지우면 된다.
 */
@Configuration
@Profile("!test")
@ConditionalOnProperty(name = "app.request-log.enabled", havingValue = "true", matchIfMissing = true)
public class DevLogConfig {

    @Bean
    public FilterRegistrationBean<RequestLogFilter> requestLogFilter(
            JwtProvider jwtProvider,
            @org.springframework.beans.factory.annotation.Value("${app.request-log.body:true}") boolean body,
            @org.springframework.beans.factory.annotation.Value("${app.request-log.max-body:2000}") int maxBody) {

        FilterRegistrationBean<RequestLogFilter> reg =
                new FilterRegistrationBean<>(new RequestLogFilter(jwtProvider, body, maxBody));
        // 시큐리티 체인(-100)보다 바깥이라 401/403 으로 튕긴 요청도 로그에 남는다.
        // 다만 CharacterEncodingFilter(HIGHEST_PRECEDENCE)보다는 한 칸 뒤 — 같은 순위로 두면 둘 중
        // 누가 먼저인지가 정해지지 않고, 인코딩 필터가 뒤로 밀리면 응답 한글이 깨진다.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
