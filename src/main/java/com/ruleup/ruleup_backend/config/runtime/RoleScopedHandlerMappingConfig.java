package com.ruleup.ruleup_backend.config.runtime;

import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 역할에 맞지 않는 컨트롤러는 <b>매핑 자체를 만들지 않는다</b>.
 *
 * <p>일반 API 에서 관리자 경로를 필터나 ALB 규칙으로 "막는" 것은 막는 쪽 설정이 하나 빠지면 다시
 * 열린다. 여기서는 핸들러 매핑 단계에서 컨트롤러를 핸들러로 인정하지 않으므로, 일반 API 에는
 * {@code /api/v1/admin/**} 라는 경로가 존재하지 않는다 — 어떤 토큰을 들고 와도 404 다.
 * 컨트롤러 빈 자체는 남겨 둔다. 관리자 패키지의 서비스·리스너(제재 후속 처리 등)는 일반 API 도 쓴다.
 *
 * <p>분류는 패키지로 한다 — 관리자 컨트롤러는 {@code ..admin..} 아래에만 둔다. 프레임워크 핸들러
 * ({@code /error} 등)는 어느 역할에서나 남긴다. Swagger 는 관리자 역할 설정에서 꺼 둔다.
 */
@Configuration
public class RoleScopedHandlerMappingConfig {

    static final String APP_PACKAGE = "com.ruleup.ruleup_backend.";
    static final String ADMIN_PACKAGE = APP_PACKAGE + "admin.";

    @Bean
    WebMvcRegistrations roleScopedRegistrations(RuntimeRoleProperties properties) {
        RuntimeRole role = properties.effectiveRole();
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new RequestMappingHandlerMapping() {
                    @Override
                    protected boolean isHandler(Class<?> beanType) {
                        return super.isHandler(beanType) && servedBy(role, ClassUtils.getUserClass(beanType));
                    }
                };
            }
        };
    }

    static boolean servedBy(RuntimeRole role, Class<?> handlerType) {
        String name = handlerType.getName();
        if (!name.startsWith(APP_PACKAGE)) return true;   // 프레임워크 핸들러
        boolean admin = name.startsWith(ADMIN_PACKAGE);
        return admin ? role.servesAdminApi() : role.servesPublicApi();
    }
}
