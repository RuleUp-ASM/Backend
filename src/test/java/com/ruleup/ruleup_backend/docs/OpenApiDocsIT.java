package com.ruleup.ruleup_backend.docs;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * OpenAPI 문서(/v3/api-docs) 계약 테스트.
 *
 * <p>Swagger 문서는 깨져도 서버가 정상 기동하기 때문에 아무도 모르는 사이 낡는다.
 * 특히 로그인·온보딩은 클라이언트가 이 문서만 보고 구현하는 구간이라,
 * "문서가 실제로 만들어지는가 + 에러 코드가 응답 예시로 실려 있는가"를 테스트로 고정한다.
 *
 * <p>문구까지 잠그지는 않는다(설명을 다듬을 때마다 테스트가 깨지면 아무도 안 다듬는다).
 * 클라이언트 구현이 실제로 의존하는 뼈대 — 경로·태그·에러 코드·스키마 — 만 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OpenApiDocsIT {

    /** 문서 생성이 한 번만 돌면 충분하므로 클래스 전체가 공유한다. */
    private static DocumentContext doc;

    @Autowired WebApplicationContext wac;

    @BeforeAll
    static void reset() {
        doc = null;
    }

    private DocumentContext doc() throws Exception {
        if (doc == null) {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
            String json = mvc.perform(get("/v3/api-docs"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            doc = JsonPath.parse(json);
        }
        return doc;
    }

    @Test
    @DisplayName("문서가 생성되고 로그인·온보딩 경로가 모두 실려 있다")
    void documentContainsAuthAndOnboardingPaths() throws Exception {
        Map<String, Object> paths = doc().read("$.paths");

        assertThat(paths).containsKeys(
                "/api/v1/intro",
                "/api/v1/auth/oauth/{provider}",
                "/api/v1/auth/oauth/{provider}/callback",
                "/api/v1/auth/signup",
                "/api/v1/auth/refresh",
                "/api/v1/auth/logout",
                "/api/v1/nicknames/check",
                "/api/v1/categories",
                "/api/v1/onboarding/me",
                "/api/v1/users/me",
                "/api/v1/users/me/profile-image");
    }

    @Test
    @DisplayName("로그인·온보딩 태그가 앱 호출 순서대로 문서 맨 앞에 온다")
    void tagsAreOrderedByCallOrder() throws Exception {
        List<String> names = doc().read("$.tags[*].name");

        assertThat(names.subList(0, 5))
                .containsExactly("Intro", "Auth", "Category", "Onboarding", "Account");
        // 같은 태그가 두 번 실리면 Swagger UI 에 그룹이 중복으로 그려진다.
        assertThat(names).doesNotHaveDuplicates();

        List<String> descriptions = doc().read("$.tags[*].description");
        assertThat(descriptions.subList(0, 5)).noneMatch(d -> d == null || d.isBlank());
    }

    @Nested
    @DisplayName("에러 코드는 응답 예시로 문서에 실린다")
    class ErrorExamples {

        @Test
        @DisplayName("소셜 로그인 — 상태코드별로 묶이고, 같은 상태의 여러 코드는 Examples 로 갈라진다")
        void loginErrors() throws Exception {
            // 400 하나에 세 코드가 함께 실린다 — 상태코드만으로 원인을 구분할 수 없다는 계약 그대로다.
            Map<String, Object> badRequest = doc().read(
                    "$.paths['/api/v1/auth/oauth/{provider}'].post.responses['400']"
                            + ".content['application/json'].examples");
            assertThat(badRequest).containsKeys("LOGIN_FAILED", "INVALID_REDIRECT_URI", "INVALID_DEVICE_INFO");

            Map<String, Object> forbidden = doc().read(
                    "$.paths['/api/v1/auth/oauth/{provider}'].post.responses['403']"
                            + ".content['application/json'].examples");
            assertThat(forbidden).containsKeys("ACCOUNT_BANNED", "INSTALLATION_ALREADY_REGISTERED");

            // IdP 장애는 502 — 가드레일 지표 대상이라 문서에서도 빠지면 안 된다.
            assertThat(doc().<Map<String, Object>>read(
                    "$.paths['/api/v1/auth/oauth/{provider}'].post.responses['502']"
                            + ".content['application/json'].examples"))
                    .containsKey("LOGIN_PROVIDER_UNAVAILABLE");
        }

        @Test
        @DisplayName("예시 본문은 실제 서버가 내려주는 공통 에러 봉투와 같은 모양이다")
        void exampleBodyMatchesEnvelope() throws Exception {
            Map<String, Object> example = doc().read(
                    "$.paths['/api/v1/auth/signup'].post.responses['400']"
                            + ".content['application/json'].examples.BIRTHDATE_UNDERAGE.value");

            assertThat(example).containsEntry("success", false);
            assertThat(example).containsKey("data");

            Map<String, Object> error = doc().read(
                    "$.paths['/api/v1/auth/signup'].post.responses['400']"
                            + ".content['application/json'].examples.BIRTHDATE_UNDERAGE.value.error");
            assertThat(error).containsEntry("code", "BIRTHDATE_UNDERAGE");
            // 메시지는 ErrorCode enum 에서 그대로 가져오므로 문구를 고치면 문서도 함께 바뀐다.
            assertThat((String) error.get("message")).contains("만 14세");
        }

        @Test
        @DisplayName("가입 — 닉네임 충돌 409 가 문서화된다")
        void signupConflicts() throws Exception {
            Map<String, Object> conflict = doc().read(
                    "$.paths['/api/v1/auth/signup'].post.responses['409']"
                            + ".content['application/json'].examples");
            assertThat(conflict).containsKeys("NICKNAME_DUPLICATED", "NICKNAME_RECENTLY_RELEASED");
        }

        @Test
        @DisplayName("닉네임 검사 — 형식 위반은 200 이라 400 이 아니라 429 만 문서화된다")
        void nicknameCheckOnlyRateLimits() throws Exception {
            Map<String, Object> responses = doc().read("$.paths['/api/v1/nicknames/check'].post.responses");
            assertThat(responses).containsKey("429").doesNotContainKey("400");
        }
    }

    @Nested
    @DisplayName("스키마 설명")
    class Schemas {

        @Test
        @DisplayName("로그인·가입 요청/응답 스키마가 이름 그대로 등록된다")
        void schemasAreRegistered() throws Exception {
            Map<String, Object> schemas = doc().read("$.components.schemas");
            assertThat(schemas).containsKeys(
                    "OAuthLoginRequest", "OAuthLoginResponse", "OAuthProfileResponse",
                    "SignupRequest", "Agreements", "AgreementItem", "SignupResponse",
                    "UserResponse", "TokenResponse", "NicknameAvailabilityResponse",
                    "CategoryListResponse", "DemographicsRequest",
                    "UserMeResponse", "WithdrawRequest", "WithdrawResponse");
        }

        @Test
        @DisplayName("분기 기준이 되는 필드에는 설명이 붙어 있다")
        void keyFieldsAreDocumented() throws Exception {
            String isNewUser = doc().read("$.components.schemas.OAuthLoginResponse.properties.isNewUser.description");
            assertThat(isNewUser).isNotBlank();

            String reason = doc().read("$.components.schemas.NicknameAvailabilityResponse.properties.reason.description");
            assertThat(reason).contains("DUPLICATED");
        }

        @Test
        @DisplayName("Bearer 인증 스킴이 등록돼 Try it out 이 동작한다")
        void securitySchemeIsRegistered() throws Exception {
            assertThat(doc().<String>read("$.components.securitySchemes.bearerAuth.scheme")).isEqualTo("bearer");
            assertThat(doc().<String>read("$.components.securitySchemes.bearerAuth.bearerFormat")).isEqualTo("JWT");
        }

        @Test
        @DisplayName("보호 API 에는 bearerAuth 요구가 걸려 있다")
        void protectedOperationsRequireBearer() throws Exception {
            assertThat(doc().<List<Object>>read("$.paths['/api/v1/users/me'].get.security")).isNotEmpty();
            assertThat(doc().<List<Object>>read("$.paths['/api/v1/onboarding/me'].put.security")).isNotEmpty();
            // 로그아웃만 auth 그룹에서 예외적으로 인증이 필요하다(다른 auth API 는 공개 경로).
            assertThat(doc().<List<Object>>read("$.paths['/api/v1/auth/logout'].post.security")).isNotEmpty();
        }
    }
}
