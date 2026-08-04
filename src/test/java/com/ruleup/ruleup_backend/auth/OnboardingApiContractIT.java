package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.Gender;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.ProfileImageStatus;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 온보딩 주변 API 계약 테스트 — 그동안 통합 테스트가 전혀 없던 구간.
 *
 * 커버 범위
 *  1) 성별 4종(MALE/FEMALE/NON_BINARY/PREFER_NOT_TO_SAY) 각각 가입 성공 + 저장값 확인
 *  2) PUT /api/v1/onboarding/me — gender 미전송 시 기존값 유지·전송 시 변경,
 *     birthDate 는 전송해도 기존 생일 유지(가입 후 수정 불가), 미인증 401
 *  3) GET /api/v1/intro — 강제 업데이트 판정·헤더 누락 400
 *  4) POST /api/v1/nicknames/check — 형식/중복/사용가능
 *  5) GET /api/v1/categories — 관심 카테고리 12종 마스터
 *  6) POST /api/v1/users/me/profile-image — 계약 경로로 등록되는지(문서 경로 정합) + 타입 검증
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OnboardingApiContractIT extends AuthApiSupport {

    /** 1x1 PNG (매직넘버 포함) — 이미지 저장·검증 경로용 최소 바이트. */
    private static final byte[] PNG_1X1 = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired WebApplicationContext wac;
    @Autowired UserRepository userRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() {
        return mvc;
    }

    private User findUser(String tag) {
        return userRepository.findByOauthProviderAndOauthSubject(OAuthProvider.KAKAO, "mock-kakao-" + tag)
                .orElseThrow();
    }

    private MvcResult putOnboarding(String accessToken, Map<String, Object> body) throws Exception {
        var request = put("/api/v1/onboarding/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body));
        if (accessToken != null) request = request.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(request).andReturn();
    }

    // ==================================================================
    // 1) 성별 4종
    // ==================================================================

    @Nested
    class 성별_저장 {

        @ParameterizedTest(name = "gender={0} 로 가입하면 그대로 저장된다")
        @ValueSource(strings = {"MALE", "FEMALE", "NON_BINARY", "PREFER_NOT_TO_SAY"})
        @DisplayName("허용 성별 4종은 각각 가입에 성공하고 저장값이 일치한다")
        void each_gender_value_signs_up(String gender) throws Exception {
            String tag = uniq("gd");
            Map<String, Object> body = preparedSignup(tag, "성별" + gender.charAt(0) + seq());
            body.put("gender", gender);

            MvcResult res = postJson("/api/v1/auth/signup", body);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat(findUser(tag).getGender()).isEqualTo(Gender.valueOf(gender));
        }

        @Test
        @DisplayName("gender 누락은 400 GENDER_REQUIRED (UI 건너뛰기여도 클라가 값을 보내야 한다)")
        void missing_gender_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq("gm"), "성별없음" + seq());
            body.remove("gender");
            expectError(postJson("/api/v1/auth/signup", body), 400, "GENDER_REQUIRED");
        }
    }

    // ==================================================================
    // 2) 온보딩 PUT
    // ==================================================================

    @Nested
    class 온보딩_수정 {

        @Test
        @DisplayName("gender 를 보내지 않으면 기존 값이 유지된다")
        void put_without_gender_keeps_existing() throws Exception {
            String tag = uniq("ok");
            String at = read(signup(tag, "유지유저" + seq()), "$.data.accessToken");
            assertThat(findUser(tag).getGender()).isEqualTo(Gender.MALE);   // 가입 시 MALE

            Map<String, Object> body = new LinkedHashMap<>();   // 빈 바디 = 전부 건너뛰기
            assertThat(putOnboarding(at, body).getResponse().getStatus()).isEqualTo(200);

            assertThat(findUser(tag).getGender()).isEqualTo(Gender.MALE);
        }

        @Test
        @DisplayName("gender 를 보내면 변경된다")
        void put_with_gender_updates() throws Exception {
            String tag = uniq("oc");
            String at = read(signup(tag, "변경유저" + seq()), "$.data.accessToken");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("gender", "FEMALE");
            assertThat(putOnboarding(at, body).getResponse().getStatus()).isEqualTo(200);

            assertThat(findUser(tag).getGender()).isEqualTo(Gender.FEMALE);
        }

        @Test
        @DisplayName("birthDate 를 보내도 기존 생일은 바뀌지 않는다 — 가입 후 수정 불가(회원 정책 §2)")
        void put_cannot_change_birthdate() throws Exception {
            String tag = uniq("ob");
            String at = read(signup(tag, "생일고정" + seq()), "$.data.accessToken");
            LocalDate before = findUser(tag).getBirthDate();
            assertThat(before).isEqualTo(LocalDate.parse("2000-05-27"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("birthDate", "1990-01-01");
            assertThat(putOnboarding(at, body).getResponse().getStatus()).isEqualTo(200);

            assertThat(findUser(tag).getBirthDate()).isEqualTo(before);
        }

        @Test
        @DisplayName("인증 없이 호출하면 401 LOGIN_REQUIRED")
        void put_requires_login() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("gender", "FEMALE");
            expectError(putOnboarding(null, body), 401, "LOGIN_REQUIRED");
        }
    }

    // ==================================================================
    // 3) 인트로 (버전 게이트)
    // ==================================================================

    @Nested
    class 인트로 {

        @Test
        @DisplayName("최신 버전이면 forceUpdate=false 로 항상 200을 준다")
        void intro_returns_200_envelope() throws Exception {
            MvcResult res = mvc.perform(get("/api/v1/intro").header("appVersionCode", 9999)).andReturn();
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.success")).isTrue();
            assertThat((Boolean) read(res, "$.data.forceUpdate")).isFalse();
        }

        @Test
        @DisplayName("최소 지원 버전 미만이면 forceUpdate=true (에러가 아니라 200 + 플래그)")
        void intro_force_update_flag() throws Exception {
            MvcResult res = mvc.perform(get("/api/v1/intro").header("appVersionCode", 0)).andReturn();
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.forceUpdate")).isTrue();
        }

        @Test
        @DisplayName("appVersionCode 헤더 누락은 400 INVALID_REQUEST")
        void intro_missing_header_rejected() throws Exception {
            expectError(mvc.perform(get("/api/v1/intro")).andReturn(), 400, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("인트로는 로그인 없이 호출할 수 있다 (스플래시 단계)")
        void intro_is_public() throws Exception {
            assertThat(mvc.perform(get("/api/v1/intro").header("appVersionCode", 100))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
        }
    }

    // ==================================================================
    // 4) 닉네임 확인
    // ==================================================================

    @Nested
    class 닉네임_확인 {

        private MvcResult check(String nickname) throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("nickname", nickname);
            return postJson("/api/v1/nicknames/check", body);
        }

        @Test
        @DisplayName("사용 가능한 닉네임은 valid·available 이 모두 true")
        void available_nickname() throws Exception {
            MvcResult res = check("사용가능" + seq());
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.valid")).isTrue();
            assertThat((Boolean) read(res, "$.data.available")).isTrue();
            assertThat((Object) read(res, "$.data.reason")).isNull();
        }

        @Test
        @DisplayName("형식 위반은 200 + valid=false, reason=FORMAT (400을 내지 않는다 — 계약)")
        void invalid_format_is_200_with_reason() throws Exception {
            MvcResult res = check("ㅏㅏㅏ");
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.valid")).isFalse();
            assertThat((String) read(res, "$.data.reason")).isEqualTo("FORMAT");
        }

        @Test
        @DisplayName("이미 쓰는 닉네임은 valid=true·available=false, reason=DUPLICATED")
        void duplicated_nickname() throws Exception {
            String tag = uniq("nk");
            String nickname = "중복확인" + tag.substring(2, 8);
            signup(tag, nickname);

            MvcResult res = check(nickname);
            assertThat((Boolean) read(res, "$.data.valid")).isTrue();
            assertThat((Boolean) read(res, "$.data.available")).isFalse();
            assertThat((String) read(res, "$.data.reason")).isEqualTo("DUPLICATED");
        }

        @Test
        @DisplayName("탈퇴자가 쓰던 닉네임은 다시 사용 가능으로 나온다")
        void nickname_of_withdrawn_user_is_available() throws Exception {
            String tag = uniq("nw");
            String nickname = "탈퇴닉" + tag.substring(2, 8);
            String at = read(signup(tag, nickname), "$.data.accessToken");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("confirmPhrase", "탈퇴할게요");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/v1/users/me")
                    .header("Authorization", "Bearer " + at)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(OM.writeValueAsString(body))).andReturn();

            assertThat((Boolean) read(check(nickname), "$.data.available")).isTrue();
        }
    }

    // ==================================================================
    // 5) 카테고리 마스터
    // ==================================================================

    @Nested
    class 카테고리_마스터 {

        @Test
        @DisplayName("관심 카테고리 12종과 최대 선택 개수(6)를 로그인 없이 조회할 수 있다")
        void category_master_is_public_and_has_12() throws Exception {
            MvcResult res = mvc.perform(get("/api/v1/categories")).andReturn();
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Integer) read(res, "$.data.maxSelectable")).isEqualTo(6);

            List<String> codes = read(res, "$.data.categories[*].code");
            assertThat(codes).hasSize(12).contains("EXERCISE", "WAKE_SLEEP", "DIET_HEALTH", "ETC");
        }
    }

    // ==================================================================
    // 6) 프로필 사진 (계약 경로)
    // ==================================================================

    @Nested
    class 프로필_사진 {

        @Test
        @DisplayName("문서 계약 경로(/api/v1/users/me/profile-image)로 사진을 등록할 수 있다")
        void upload_via_contract_path() throws Exception {
            String tag = uniq("pi");
            String at = read(signup(tag, "사진유저" + seq()), "$.data.accessToken");

            MvcResult res = mvc.perform(multipart("/api/v1/users/me/profile-image")
                    .file(new MockMultipartFile("image", "p.png", MediaType.IMAGE_PNG_VALUE, PNG_1X1))
                    .header("Authorization", "Bearer " + at)).andReturn();

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.profileImageUrl")).contains("/files/");

            User user = findUser(tag);
            assertThat(user.getProfileImageUrl()).isNotNull();
            // 등록 직후엔 승인 전 — 타인에게는 기본 프로필(null)이 보인다
            assertThat(user.getProfileImageStatus())
                    .isIn(ProfileImageStatus.PENDING, ProfileImageStatus.APPROVED);
        }

        @Test
        @DisplayName("이미지가 아닌 파일은 415 IMAGE_INVALID_TYPE")
        void upload_rejects_non_image() throws Exception {
            String tag = uniq("pt");
            String at = read(signup(tag, "잘못파일" + seq()), "$.data.accessToken");

            MvcResult res = mvc.perform(multipart("/api/v1/users/me/profile-image")
                    .file(new MockMultipartFile("image", "a.txt", MediaType.TEXT_PLAIN_VALUE,
                            "not an image".getBytes()))
                    .header("Authorization", "Bearer " + at)).andReturn();

            expectError(res, 415, "IMAGE_INVALID_TYPE");
        }

        @Test
        @DisplayName("사진 등록은 로그인 필요 — 401 LOGIN_REQUIRED")
        void upload_requires_login() throws Exception {
            MvcResult res = mvc.perform(multipart("/api/v1/users/me/profile-image")
                    .file(new MockMultipartFile("image", "p.png", MediaType.IMAGE_PNG_VALUE, PNG_1X1)))
                    .andReturn();
            expectError(res, 401, "LOGIN_REQUIRED");
        }
    }
}
