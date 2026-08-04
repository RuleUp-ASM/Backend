package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.auth.domain.SocialToken;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.RandomTempNicknameGenerator;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 임시 승인 닉네임의 <b>실제 DB UNIQUE 충돌 → 재시도</b> 검증 (DB 정리 문서 §6).
 *
 * <p>할당기 단위 테스트({@code UserTempNicknameTest})는 "사전 검사에서 걸린 경우"만 덮는다.
 * 여기서는 사전 검사를 통과한 값이 INSERT 시점에 충돌하는 경합(TOCTOU)을 실제 MySQL 제약으로
 * 재현한다. 값이 난수라 그냥은 충돌을 만들 수 없어 두 빈을 스텁한다.
 * <ul>
 *   <li>{@link RandomTempNicknameGenerator} — 첫 후보를 "이미 점유된 값"으로 고정</li>
 *   <li>{@link UserRepository#isNicknameTaken} — false 로 고정해 사전 검사를 통과시킴(= 경합 창 재현)</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TempNicknameCollisionIT extends AuthApiSupport {

    @Autowired WebApplicationContext wac;
    @MockitoSpyBean RandomTempNicknameGenerator tempNicknameGenerator;
    @MockitoSpyBean UserRepository userRepository;
    @Autowired SocialTokenRepository socialTokenRepository;

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

    /**
     * 지정한 승인 닉네임을 점유한 사용자를 DB에 직접 넣는다.
     * 가입 API로 만들면 비동기 검수가 승인되면서 approved_nickname 이 신청값으로 바뀌어
     * 점유가 풀린다 — 충돌을 재현하려면 값이 고정돼 있어야 한다.
     */
    private User blockerHolding(String approvedNickname) {
        User blocker = User.create(OAuthProvider.GOOGLE, "blocker-" + uniq("b"), null,
                "점유자" + seq(), null, null);
        blocker.assignApprovedNickname(approvedNickname);
        return userRepository.saveAndFlush(blocker);
    }

    @Test
    @DisplayName("DB가 임시 승인 닉네임 중복을 실제로 거부한다 — 재시도가 기대는 안전망")
    void unique_constraint_rejects_duplicate_approved_nickname() throws Exception {
        String occupied = "cafebabe";
        blockerHolding(occupied);

        User clash = User.create(OAuthProvider.GOOGLE, "sub-" + uniq("g"), null,
                "충돌유저" + seq(), null, null);
        clash.assignApprovedNickname(occupied);   // 같은 승인 닉네임을 강제

        assertThatThrownBy(() -> userRepository.saveAndFlush(clash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("사전 검사를 통과한 후보가 INSERT 에서 충돌하면, 새 후보로 재시도해 가입이 성공한다")
    void signup_retries_on_insert_collision() throws Exception {
        // 1) 점유된 승인 닉네임 하나를 확보
        String occupied = "deadbeef";
        blockerHolding(occupied);

        // 2) 경합 창 재현: 사전 검사는 "비어 있다"고 답하게 하고, 첫 후보를 점유된 값으로 고정
        doReturn(false).when(userRepository).isNicknameTaken(anyString(), any());
        doReturn(occupied)                    // 1회차 → INSERT 에서 UNIQUE 충돌
                .doCallRealMethod()           // 2회차 → 정상 난수 후보
                .when(tempNicknameGenerator).next();

        // 3) 가입은 재시도 덕분에 성공해야 한다
        String tag = uniq("rt");
        MvcResult res = postJson("/api/v1/auth/signup", preparedSignup(tag, "재시도유저" + seq()));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        // 충돌한 값은 버려졌다. (커밋 후 비동기 검수가 승인되면 신청 닉네임으로 바뀔 수 있어
        //  "8자리 hex"로 고정하지 않고, 점유된 값이 아니라는 것만 확인한다)
        User created = findUser(tag);
        assertThat(created.getApprovedNickname()).isNotEqualTo(occupied);
        verify(tempNicknameGenerator, atLeast(2)).next();          // 최소 한 번은 재발급했다
        verify(userRepository, times(2)).save(any(User.class));    // INSERT 는 정확히 두 번 시도됐다
    }

    @Test
    @DisplayName("재시도로 성공한 가입도 데이터가 온전하다 — 롤백된 1회차 흔적이 남지 않는다")
    void retried_signup_leaves_no_partial_rows() throws Exception {
        String occupied = "feedface";
        blockerHolding(occupied);

        long before = userRepository.count();

        doReturn(false).when(userRepository).isNicknameTaken(anyString(), any());
        doReturn(occupied).doCallRealMethod().when(tempNicknameGenerator).next();

        String tag = uniq("rt2");
        String nickname = "온전유저" + seq();
        MvcResult res = postJson("/api/v1/auth/signup", preparedSignup(tag, nickname));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        // 1회차 트랜잭션은 통째로 롤백됐으므로 사용자 행은 정확히 하나만 늘어난다
        assertThat(userRepository.count()).isEqualTo(before + 1);

        UUID userId = UUID.fromString(read(res, "$.data.user.id"));
        User created = userRepository.findById(userId).orElseThrow();
        assertThat(created.getNickname()).isEqualTo(nickname);
        assertThat(created.getBirthDate()).isNotNull();                       // user_information 도 함께
        assertThat(created.getInterestCategories()).isNotEmpty();             // user_interests 도 함께
        assertThat((Integer) read(res, "$.data.user.score")).isEqualTo(10);   // 점수 요약도 정상

        // social_tokens 도 저장돼야 한다. 보류분(in-memory)은 트랜잭션과 함께 롤백되지 않으므로,
        // 1회차에서 미리 지워버리면 재시도가 토큰을 못 찾아 unlink 근거가 조용히 사라진다.
        assertThat(socialTokenRepository.findById(new SocialToken.Key(userId, OAuthProvider.KAKAO)))
                .as("재시도로 성공한 가입도 IdP 토큰을 보존해야 한다")
                .isPresent();
    }

    @Test
    @DisplayName("재시도 상한을 넘도록 계속 충돌하면 조용히 넘어가지 않는다")
    void gives_up_after_max_attempts() throws Exception {
        String occupied = "badc0ffe";
        blockerHolding(occupied);

        // 사전 검사는 계속 통과시키고, 후보는 항상 점유된 값 → 매 시도가 INSERT 충돌
        doReturn(false).when(userRepository).isNicknameTaken(anyString(), any());
        doReturn(occupied).when(tempNicknameGenerator).next();

        String tag = uniq("rt3");
        MvcResult res = postJson("/api/v1/auth/signup", preparedSignup(tag, "포기유저" + seq()));

        // 상한만큼 "실제로" 트랜잭션을 다시 열었는지 — 생성기 호출 수는 사전 검사 루프와 섞이므로
        // INSERT 시도(=save) 횟수로 센다.
        verify(userRepository, times(AuthService.MAX_SIGNUP_ATTEMPTS)).save(any(User.class));

        // 사용자 입력 문제가 아니므로 닉네임 중복(409)으로 위장하지 않는다
        assertThat(res.getResponse().getStatus()).isEqualTo(500);
        assertThat((Boolean) read(res, "$.success")).isFalse();
        assertThat((String) read(res, "$.error.code")).isEqualTo("INTERNAL_SERVER_ERROR");
    }
}
