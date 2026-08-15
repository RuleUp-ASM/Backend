package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 가입 경합·입력 정규화 계약.
 *
 * <ol>
 *   <li>동일 소셜 계정 동시 가입 — "경합 시 후발 요청은 기존 유저 로그인으로 수렴"(테크 스펙 4-3).
 *       사전 조회와 INSERT 사이에 다른 요청이 먼저 가입하면 {@code uq_users_oauth_identity} 가
 *       터지는데, 지금은 그게 500 으로 나가 계약과 다르다.</li>
 *   <li>중복 관심 카테고리 — {@code user_interests} PK(user_id, category) 위반으로 500 이 나면
 *       안 된다. 클라 버그로 가입을 막지 않도록 서버가 중복을 제거한다.</li>
 * </ol>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SignupRaceContractIT extends AuthApiSupport {

    @Autowired WebApplicationContext wac;
    @MockitoSpyBean UserRepository userRepository;

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

    @Nested
    @DisplayName("동시 가입")
    class ConcurrentSignup {

        @Test
        @DisplayName("사전 조회를 통과한 뒤 (provider, subject) 가 이미 있으면 기존 유저 로그인으로 수렴한다")
        void concurrent_signup_converges_to_login() throws Exception {
            String tag = uniq("race");

            // 같은 소셜 계정으로 signupToken 두 개를 확보한다(아직 계정 없음 → 둘 다 신규 분기).
            String tokenA = issueSignupToken(tag, "inst-" + tag + "-a", "dev-" + tag + "-a");
            String tokenB = issueSignupToken(tag, "inst-" + tag + "-b", "dev-" + tag + "-b");

            // A 가 먼저 가입에 성공한다.
            MvcResult first = postJson("/api/v1/auth/signup",
                    signupBody(tokenA, "선착순" + seq(), "inst-" + tag + "-a", "dev-" + tag + "-a"));
            assertThat(first.getResponse().getStatus()).isEqualTo(200);
            String userId = read(first, "$.data.user.id");
            long afterFirst = userRepository.count();

            // B 는 A 의 INSERT 직전에 조회를 마친 상태 — 경합 창을 재현한다(첫 조회만 "없음").
            // (JPA 리포지토리는 인터페이스라 스파이가 실제 메서드를 호출할 수 없어, 이후 조회 결과는
            //  미리 읽어 둔 엔티티로 돌려준다.)
            User created = findUser(tag);
            doReturn(Optional.empty()).doReturn(Optional.of(created))
                    .when(userRepository).findByOauthProviderAndOauthSubject(any(), anyString());

            MvcResult second = postJson("/api/v1/auth/signup",
                    signupBody(tokenB, "후착순" + seq(), "inst-" + tag + "-b", "dev-" + tag + "-b"));
            org.mockito.Mockito.reset(userRepository);   // 이후 단언은 실제 DB 를 읽는다

            // 500 이 아니라 "기존 유저 로그인"으로 수렴해야 한다
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(second, "$.data.isNewUser")).isFalse();
            assertThat((String) read(second, "$.data.user.id")).isEqualTo(userId);
            assertThat((String) read(second, "$.data.accessToken")).isNotBlank();

            // 계정은 하나뿐이고, 후발 요청의 닉네임으로 덮어써지지 않는다
            assertThat(userRepository.count()).isEqualTo(afterFirst);
            assertThat(findUser(tag).getNickname()).startsWith("선착순");
        }
    }

    @Nested
    @DisplayName("중복 카테고리")
    class DuplicateCategories {

        @Test
        @DisplayName("중복 관심 카테고리는 서버가 제거하고 가입을 통과시킨다 — 500 이 아니다")
        void duplicate_categories_are_deduplicated() throws Exception {
            String tag = uniq("dupcat");
            Map<String, Object> body = preparedSignup(tag, "중복카테" + seq());
            body.put("interestCategories", List.of("EXERCISE", "EXERCISE", "READING", "EXERCISE"));

            MvcResult res = postJson("/api/v1/auth/signup", body);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            List<String> saved = read(res, "$.data.user.interestCategories");
            assertThat(saved).containsExactlyInAnyOrder("EXERCISE", "READING");
            assertThat(findUser(tag).getInterestCategories())
                    .containsExactlyInAnyOrder("EXERCISE", "READING");
        }

        @Test
        @DisplayName("중복을 제거한 뒤 개수를 센다 — 중복 때문에 6개 상한에 걸리지 않는다")
        void limit_is_checked_after_deduplication() throws Exception {
            String tag = uniq("dupcnt");
            Map<String, Object> body = preparedSignup(tag, "상한중복" + seq());
            // 원본 8개지만 고유값은 6개 → 통과해야 한다
            body.put("interestCategories", List.of(
                    "EXERCISE", "EXERCISE", "WAKE_SLEEP", "DIET_HEALTH",
                    "STUDY", "READING", "MIND", "MIND"));

            MvcResult res = postJson("/api/v1/auth/signup", body);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((List<String>) read(res, "$.data.user.interestCategories")).hasSize(6);
        }

        @Test
        @DisplayName("고유값이 6개를 넘으면 여전히 400 INTEREST_LIMIT_EXCEEDED")
        void distinct_over_limit_still_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq("dupover"), "초과중복" + seq());
            body.put("interestCategories", List.of(
                    "EXERCISE", "WAKE_SLEEP", "DIET_HEALTH", "STUDY",
                    "READING", "MIND", "FINANCE"));
            expectError(postJson("/api/v1/auth/signup", body), 400, "INTEREST_LIMIT_EXCEEDED");
        }
    }
}
