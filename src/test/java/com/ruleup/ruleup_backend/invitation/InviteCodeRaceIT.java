package com.ruleup.ruleup_backend.invitation;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 친구 초대 코드는 <b>유저당 하나</b>이고, 없으면 조회하면서 만든다.
 *
 * <h4>두 요청이 동시에 「아직 없다」를 읽으면</h4>
 * 한쪽의 INSERT 가 유저 유일 제약에 걸린다. 그 경합은 정상 경로다 — 진 쪽은 먼저 만들어진
 * 코드를 그대로 돌려주면 된다. 문제는 <b>어디서 지느냐</b>다. 실패하는 INSERT 를 요청
 * 트랜잭션 안에서 하면 JPA 가 그 트랜잭션에 rollback-only 를 새겨, 예외를 잡아 먼저
 * 만들어진 코드를 찾아내도 커밋이 {@code UnexpectedRollbackException} 으로 끝나 500 이 된다.
 *
 * <p>여기서는 그 경합을 <b>결정적으로</b> 만든다. 먼저 코드를 하나 만들어 두고, 늦게 처리되는
 * 요청이 「없음」을 읽었던 상태를 재현하려고 <b>첫 조회 한 번만</b> 비어 있는 결과로 돌려준다.
 * INSERT·유일 제약 위반·트랜잭션 처리는 실제 코드와 DB 가 그대로 한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InviteCodeRaceIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoSpyBean InviteCodeRepository inviteCodeRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @Test
    @DisplayName("동시에 처음 조회해도 먼저 만들어진 같은 코드로 모인다")
    void concurrentFirstLookupConvergesToTheExistingCode() throws Exception {
        Member me = member(uniq("invite-race"));

        MvcResult first = getAuth("/api/v1/me/invitation", me.token());
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String code = read(first, "$.data.inviteCode");
        assertThat(code).isNotBlank();

        // 늦게 처리되는 요청이 INSERT 직전에 읽었던 「없음」을 재현한다 — <b>첫 조회 한 번만</b>이다.
        // 그 다음 조회는 지금 DB 에 실제로 들어 있는 행을 돌려준다(경합 상대가 커밋해 둔 행).
        Optional<com.ruleup.ruleup_backend.invitation.domain.InviteCode> stored =
                inviteCodeRepository.findByUserId(me.id());
        assertThat(stored).isPresent();
        doReturn(Optional.empty()).doReturn(stored)
                .when(inviteCodeRepository).findByUserId(me.id());

        MvcResult second = getAuth("/api/v1/me/invitation", me.token());

        assertThat(second.getResponse().getStatus())
                .as("경합에서 진 요청이 500 으로 끝나면, 초대 화면이 처음 여는 순간에만 무작위로 터진다")
                .isEqualTo(200);
        assertThat((String) read(second, "$.data.inviteCode"))
                .as("코드는 유저당 하나다 — 진 쪽도 같은 코드를 본다").isEqualTo(code);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM InviteCode WHERE userId = ?", Integer.class, bytes(me.id())))
                .as("두 번째 요청이 행을 하나 더 만들지 않는다").isEqualTo(1);
    }
}
