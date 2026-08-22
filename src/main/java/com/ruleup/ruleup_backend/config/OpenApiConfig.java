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

            ### 그룹 챌린지 방 내부 (Phase 1)
            방 안 API 는 전부 **그 방의 ACTIVE 멤버 전용**이다. 비멤버는 403 `NOT_CHALLENGE_MEMBER` 이며,
            비멤버 화면은 탐색 모듈의 공개 상세(`GET /api/v1/challenges/{challengeId}`)가 담당한다.

            1. `GET /api/v1/challenges/{challengeId}/room` — 방 진입 시 화면 전체를 한 번에 채우는 일괄 조회
            2. `GET /api/v1/challenges/{challengeId}/threads` — 인증 이벤트 피드(커서 페이징)
            3. `GET /api/v1/challenges/{challengeId}/ranking` · `/members` — 랭킹·멤버 목록

            읽음/미읽음 표시는 **정책상 영구 미제공**이다. 공지·댓글은 Phase 2 로 이관돼 이 문서에 없다 —
            그래서 Phase 1 의 스레드는 인증 성공/실패 이벤트만 흐르는 피드다.

            두 가지는 서버가 절대 어기지 않는 약속이라 클라이언트도 그 전제로 그리면 된다.
            - **실패 이벤트는 이의 가능 기간(1일)이 지난 뒤에만** 내려간다. 그래서 발생일보다 늦게 도착하며,
              `failDate`(원래 날짜)로 "○월 ○일 루틴을 실패했습니다"처럼 **과거형**으로 표시해야 한다.
              이의가 인용된 실패는 영원히 내려가지 않는다.
            - **내가 차단한 사람**은 임시 닉네임 + 기본 이미지로 가려서 내려간다(`user.blocked=true`).
              목록에서 빠지지는 않는다 — 빠지면 피드에 구멍이 생겨 맥락이 무너지기 때문이다.
            """;

    /**
     * 화면 맨 위에 오는 태그 — 앱이 실제로 호출하는 순서다.
     * 인트로 → 로그인/가입 → 온보딩 → 계정/프로필 → 방에 들어가서 하는 일(가입 → 초대 → 방 안 → 랭킹 → 운영 → 신고).
     * 여기 없는 태그는 뒤에 이름순으로 붙는다({@link #tagOrderCustomizer()}).
     */
    private static final List<Tag> APP_FLOW_TAGS = List.of(
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
                    .description("프로필 조회 · 수정 · 사진 — 검수(PENDING/APPROVED/REJECTED)에 따라 타인에게 보이는 값이 달라진다"),
            new Tag().name("Challenge Member")
                    .description("챌린지 가입 · 멤버 목록 · 탈퇴 — 방 안 API 를 쓰려면 먼저 여기를 통과해야 한다"),
            new Tag().name("Challenge Invitation")
                    .description("초대 링크 발급(방장) · 조회 · 수락 — 비공개 방에 들어오는 유일한 경로"),
            new Tag().name("Challenge Room")
                    .description("방 홈 일괄 조회 · 인증 이벤트 스레드 · 방 안 랭킹 — 전부 ACTIVE 멤버 전용"),
            new Tag().name("인증 구현 - 챌린지")
                    .description("셋업(인증 장소·측정 대상 앱) · 오늘 인증 결과 · 수동 인증 제출 — 방 안 인증 화면이 쓰는 API"),
            new Tag().name("인증 구현")
                    .description("인증 신호 전송(sync) · 판정 결과 확인(ack) · 수동 인증 취소 · 진행률 일괄 조회"),
            new Tag().name("Challenge Ranking")
                    .description("방 밖 랭킹 — 같은 모드(GROUP/SOLO)끼리 챌린지를 비교한다. 하루 1회 03시 갱신"),
            new Tag().name("Challenge Admin")
                    .description("방장 전용 운영 — 멤버 강퇴 · 방장 권한 넘기기 · 봇방장 클레임"),
            new Tag().name("Report")
                    .description("신고 접수 · 블랙리스트 — 차단 효과는 내 화면에만 적용된다"));

    @Bean
    public OpenAPI ruleupOpenAPI() {
        OpenAPI openAPI = new OpenAPI()
                .info(new Info()
                        .title("RuleUp API")
                        .description(DESCRIPTION)
                        .version("v1"))
                .tags(new ArrayList<>(APP_FLOW_TAGS))
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
     * <p>규칙: 앱 호출 순서 태그를 순서대로 맨 앞에, 나머지는 이름순으로 뒤에. 같은 이름은 하나로 합치고
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
            for (Tag flowTag : APP_FLOW_TAGS) {
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