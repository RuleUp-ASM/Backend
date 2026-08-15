package com.ruleup.ruleup_backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 문서 상단 설명 — 개별 API 설명에 반복해서 적지 않아도 되도록 공통 규약을 여기에 모은다.
     * (봉투 / 인증 / 에러 코드 / 로그인·온보딩 호출 순서)
     */
    private static final String DESCRIPTION = """
            RuleUp 서버 API 문서.

            ### 공통 응답 봉투
            성공·실패 모두 같은 3필드 봉투로 내려간다. HTTP 상태코드는 실제 상태를 그대로 쓴다.
            ```
            성공  { "success": true,  "data": { ... }, "error": null }
            실패  { "success": false, "data": null,    "error": { "code": "...", "message": "..." } }
            ```
            같은 상태코드를 여러 원인이 공유하므로(400 하나에 여러 코드) 분기는 `error.code` 로 한다.
            `error.message` 는 사용자에게 그대로 노출해도 되는 문구다.

            ### 인증
            보호 API는 `Authorization: Bearer {accessToken}` 이 필요하다.
            우측 상단 **Authorize** 에 accessToken 을 넣으면 Try it out 이 그대로 동작한다.
            accessToken 이 만료되면 `POST /api/v1/auth/refresh` 로 회전시킨다(refreshToken 도 함께 새로 발급된다).

            ### 로그인 ~ 온보딩 호출 순서
            1. `GET /api/v1/intro` — 강제 업데이트 판정 + 현행 약관 버전 6종 수령 (로그인 전, 토큰 불필요)
            2. `POST /api/v1/auth/oauth/{provider}` — 인가코드 검증
               · 기존 회원(`isNewUser=false`) → accessToken/refreshToken 수령, 여기서 끝
               · 신규(`isNewUser=true`) → `signupToken` + 프리필 힌트 수령, 3번으로
            3. `GET /api/v1/categories` · `POST /api/v1/nicknames/check` — 온보딩 화면 입력 보조
            4. `POST /api/v1/auth/signup` — signupToken 과 함께 온보딩 입력을 한 번에 제출 → 앱 토큰 발급
            5. `POST /api/v1/users/me/profile-image` — 프로필 사진(선택, 가입 후 accessToken 으로 별도 호출)
            """;

    /**
     * 화면 맨 위에 오는 태그 — 앱이 실제로 호출하는 순서(인트로 → 로그인/가입 → 온보딩 → 계정 → 프로필)다.
     * 여기 없는 태그는 뒤에 이름순으로 붙는다({@link #tagOrderCustomizer()}).
     */
    private static final List<Tag> ONBOARDING_FLOW_TAGS = List.of(
            new Tag().name("Intro")
                    .description("앱 인트로 · 버전 게이트 — 로그인 전 스플래시에서 호출(토큰 불필요)"),
            new Tag().name("Auth")
                    .description("소셜 로그인 · 회원가입 · 토큰 재발급/로그아웃 · 닉네임 검사"),
            new Tag().name("Category")
                    .description("관심 카테고리 마스터 — 가입·프로필 화면의 선택지"),
            new Tag().name("Onboarding")
                    .description("가입 후 최초 접속 시 수집하는 선택 정보"),
            new Tag().name("Account")
                    .description("내 프로필 조회 · 프로필 사진 등록 · 회원 탈퇴"),
            new Tag().name("Profile")
                    .description("프로필 조회 · 수정 · 사진 — 검수(PENDING/APPROVED/REJECTED)에 따라 타인에게 보이는 값이 달라진다"));

    @Bean
    public OpenAPI ruleupOpenAPI() {
        OpenAPI openAPI = new OpenAPI()
                .info(new Info()
                        .title("RuleUp API")
                        .description(DESCRIPTION)
                        .version("v1"))
                .tags(new ArrayList<>(ONBOARDING_FLOW_TAGS))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("가입/로그인 응답의 accessToken 을 그대로 입력한다(\"Bearer \" 접두어는 UI가 붙인다).")));

        // 외부 도메인이 주입됐을 때만 server를 명시 (로컬은 비워두면 자동 처리)
        if (serverUrl != null && !serverUrl.isBlank()) {
            openAPI.addServersItem(new Server().url(serverUrl).description("public"));
        }
        return openAPI;
    }

    /**
     * 태그 순서·중복 정리.
     *
     * <p>springdoc 은 컨트롤러를 스캔하며 태그를 뒤에 이어 붙이기 때문에, OpenAPI 빈에 미리 선언한 순서가
     * 그대로 유지되지 않는다(같은 이름이 두 번 실리기도 한다). 문서를 여는 사람이 매번 다른 순서를 보게 되므로
     * 생성이 끝난 뒤 한 번 정리한다.
     *
     * <p>규칙: 로그인·온보딩 흐름 태그를 호출 순서대로 맨 앞에, 나머지는 이름순으로 뒤에. 같은 이름은 하나로 합치고
     * 설명이 있는 쪽을 남긴다.
     */
    @Bean
    public OpenApiCustomizer tagOrderCustomizer() {
        return openApi -> {
            if (openApi.getTags() == null || openApi.getTags().isEmpty()) return;

            Map<String, Tag> byName = new LinkedHashMap<>();
            for (Tag tag : openApi.getTags()) {
                byName.merge(tag.getName(), tag, (kept, other) -> hasText(kept.getDescription()) ? kept : other);
            }

            List<Tag> ordered = new ArrayList<>();
            for (Tag flowTag : ONBOARDING_FLOW_TAGS) {
                Tag found = byName.remove(flowTag.getName());
                if (found != null) {
                    // 스캔된 태그에 설명이 없으면 여기 선언한 설명을 입힌다.
                    ordered.add(hasText(found.getDescription()) ? found : flowTag);
                }
            }
            byName.values().stream()
                    .sorted(Comparator.comparing(Tag::getName))
                    .forEach(ordered::add);

            openApi.setTags(ordered);
        };
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}