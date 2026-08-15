package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.moderation.ModerationResult;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.NicknameStatus;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * 닉네임 변경 생애주기 — <b>이전 닉네임은 새 닉네임이 승인될 때까지 점유된다.</b>
 *
 * <p>회원 정책 §3·§4.1(거부 시 직전 승인본으로 복귀)의 실질적 요구.
 * 심사 중에 이전 닉네임이 풀려 남이 채가면, 심사가 거부됐을 때 돌아갈 자리가 없어진다.
 * 그래서 보호 구간은 정확히 <b>심사 중</b>이다:
 *  1) 새 닉네임 <b>승인 전</b>까지는 점유(DUPLICATED) — 거부 시 돌아갈 자리 보존(2026-08-04 확정)
 *  2) 승인되는 <b>순간</b> 이전 닉네임은 풀린다 — 별도 유예 기간은 없다(1주일 잠금 폐지)
 *
 * <p>검수는 비동기라 자동 승인되면 관측 창이 사라진다 → 이 테스트에서는 검수를 "보류"로
 * 고정해 심사 중 상태를 유지하고, 승인은 직접 반영해 전후를 비교한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NicknameLifecycleIT extends AuthApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired UserRepository userRepository;

    /** 검수 보류(UNAVAILABLE)로 고정 — 비동기 자동 승인이 심사 중 구간을 지워버리지 않게 한다. */
    @MockitoBean ContentModerationClient moderationClient;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        when(moderationClient.moderateNickname(anyString())).thenReturn(ModerationResult.UNAVAILABLE);
        when(moderationClient.moderateImage(anyString())).thenReturn(ModerationResult.UNAVAILABLE);
        when(moderationClient.moderateImageBytes(any(), anyString())).thenReturn(ModerationResult.UNAVAILABLE);
    }

    @Override
    protected MockMvc mvc() {
        return mvc;
    }

    private User findUser(String tag) {
        return userRepository.findByOauthProviderAndOauthSubject(OAuthProvider.KAKAO, "mock-kakao-" + tag)
                .orElseThrow();
    }

    private MvcResult check(String nickname) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nickname", nickname);
        return postJson("/api/v1/nicknames/check", body);
    }

    /** 닉네임 사용 가능 여부 — check API 의 available. */
    private boolean isAvailable(String nickname) throws Exception {
        return read(check(nickname), "$.data.available");
    }

    /** 사용 불가 사유 — 지금은 FORMAT / DUPLICATED 두 가지뿐이다. */
    private String reasonFor(String nickname) throws Exception {
        return read(check(nickname), "$.data.reason");
    }

    private MvcResult changeNickname(String accessToken, String nickname) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nickname", nickname);
        return mvc.perform(patch("/api/v1/profile")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    /** 검수 승인을 직접 반영한다(비동기 검수는 보류로 고정돼 있다). */
    private void approveNickname(String tag) {
        User user = findUser(tag);
        user.approveNickname();
        userRepository.saveAndFlush(user);
    }

    @Test
    @DisplayName("새 닉네임 심사 중에는 이전 닉네임이 계속 점유된다 — 남이 채갈 수 없다")
    void previousNicknameStaysReservedWhileNewOneIsUnderReview() throws Exception {
        String tag = uniq("nl");
        String oldNickname = "이전닉" + tag.substring(2, 8);
        String newNickname = "새닉" + tag.substring(2, 8);

        String at = read(signup(tag, oldNickname), "$.data.accessToken");
        approveNickname(tag);                                   // 이전 닉네임이 승인 상태가 된다
        assertThat(findUser(tag).getApprovedNickname()).isEqualTo(oldNickname);

        assertThat(changeNickname(at, newNickname).getResponse().getStatus()).isEqualTo(200);

        User afterChange = findUser(tag);
        assertThat(afterChange.getNickname()).isEqualTo(newNickname);
        assertThat(afterChange.getNicknameStatus()).isEqualTo(NicknameStatus.PENDING);
        assertThat(afterChange.getApprovedNickname())
                .as("심사 중에는 직전 승인본이 유지돼야 타인 화면·복귀 자리가 보존된다")
                .isEqualTo(oldNickname);

        assertThat(isAvailable(oldNickname)).as("이전 닉네임은 아직 점유 중").isFalse();
        assertThat(isAvailable(newNickname)).as("신청 중인 새 닉네임도 점유 중").isFalse();
    }

    @Test
    @DisplayName("새 닉네임이 승인되는 순간 이전 닉네임이 풀린다 — 유예 기간 없이 곧바로 남이 쓸 수 있다")
    void previousNicknameIsReleasedTheMomentTheNewOneIsApproved() throws Exception {
        String tag = uniq("nl2");
        String oldNickname = "해제전" + tag.substring(3, 8);
        String newNickname = "해제후" + tag.substring(3, 8);

        String at = read(signup(tag, oldNickname), "$.data.accessToken");
        approveNickname(tag);
        changeNickname(at, newNickname);
        assertThat(reasonFor(oldNickname)).as("심사 중에는 점유가 이유").isEqualTo("DUPLICATED");

        approveNickname(tag);                                   // 새 닉네임 승인 → 점유 해제

        assertThat(findUser(tag).getApprovedNickname()).isEqualTo(newNickname);
        assertThat(isAvailable(oldNickname)).as("승인과 동시에 사용 가능해진다").isTrue();
        assertThat((Object) reasonFor(oldNickname)).isNull();

        // 다른 사람이 곧바로 가져갈 수 있다(1주일 잠금 폐지 — 보호 구간은 심사 중으로 한정)
        MvcResult taken = postJson("/api/v1/auth/signup", preparedSignup(uniq("nl2o"), oldNickname));
        assertThat(taken.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) read(taken, "$.data.user.nickname")).isEqualTo(oldNickname);
    }

    @Test
    @DisplayName("심사 중 이전 닉네임으로 다른 사람이 가입하려 하면 409 NICKNAME_DUPLICATED")
    void othersCannotTakePreviousNicknameDuringReview() throws Exception {
        String tag = uniq("nl3");
        String oldNickname = "선점방지" + tag.substring(3, 7);

        String at = read(signup(tag, oldNickname), "$.data.accessToken");
        approveNickname(tag);
        changeNickname(at, "바뀐닉" + tag.substring(3, 7));

        expectError(postJson("/api/v1/auth/signup", preparedSignup(uniq("nl3o"), oldNickname)),
                409, "NICKNAME_DUPLICATED");
    }
}
