package com.ruleup.ruleup_backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI / OpenAPI 문서 설정.
 *
 * [server URL]
 *  Swagger UI의 "Try it out"은 "어느 주소로 요청을 쏠지"를 server URL에서 가져온다.
 *  EC2 + Cloudflare 터널처럼 리버스 프록시 뒤에 있으면, 앱이 보는 자기 주소는
 *  http://localhost:8080 이라서 그대로 두면 외부에서 호출이 안 된다.
 *  - SWAGGER_SERVER_URL 환경변수가 있으면 그 값을 server로 박는다 (가장 확실).
 *    예: SWAGGER_SERVER_URL=https://api.ruleup.app
 *  - 비어 있으면 server를 지정하지 않고, application.yaml의
 *    server.forward-headers-strategy=framework 가 X-Forwarded-* 헤더로 자동 보정하게 둔다.
 */
@Configuration
public class OpenApiConfig {

    @Value("${app.swagger.server-url:}")
    private String serverUrl;

    @Bean
    public OpenAPI ruleupOpenAPI() {
        OpenAPI openAPI = new OpenAPI()
                .info(new Info()
                        .title("RuleUp API")
                        .description("로그인 · 온보딩 · 프로필 API 문서")
                        .version("v1"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));

        // 외부 도메인이 주입됐을 때만 server를 명시 (로컬은 비워두면 자동 처리)
        if (serverUrl != null && !serverUrl.isBlank()) {
            openAPI.addServersItem(new Server().url(serverUrl).description("public"));
        }
        return openAPI;
    }
}