package com.ruleup.ruleup_backend.admin;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.domain.AdminAuditLog;
import com.ruleup.ruleup_backend.admin.repository.AdminAuditLogRepository;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 운영자 콘솔 진입 — 백오피스 공통 5-2-1 B.
 *
 * <p>비밀번호 하나로 들어오지만 <b>세션은 실제 운영자 계정의 것</b>이다. 그래야 감사 로그가
 * 조작자를 남길 수 있고, 뒤에 오는 접근 통제·감사가 그대로 동작한다.
 */
@SpringBootTest(properties = "app.admin.passcode=console-test-pass")
@Import(TestcontainersConfiguration.class)
class AdminConsoleAuthIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AdminAuditLogRepository auditLogRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() {
        return mvc;
    }

    @Override
    protected JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    private MvcResult login(String passcode) throws Exception {
        return loginWith("passcode", passcode);
    }

    private MvcResult loginWith(String field, String value) throws Exception {
        return mvc.perform(post("/api/v1/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of(field, value)))).andReturn();
    }

    @Test
    @DisplayName("본문 키는 password 도 passcode 도 된다 — 이미 배포된 화면을 고치게 하지 않는다")
    void accepts_both_field_names() throws Exception {
        assertThat(loginWith("password", "console-test-pass").getResponse().getStatus())
                .as("콘솔이 보내는 이름").isEqualTo(200);
        assertThat(loginWith("passcode", "console-test-pass").getResponse().getStatus())
                .as("서버가 계약으로 잡았던 이름").isEqualTo(200);

        // 키만 맞고 값이 틀리면 그대로 401 이다 — 둘 다 받는 것이 검증을 무르게 하지 않는다.
        expectError(loginWith("password", "nope"), 401, "INVALID_PASSCODE");
    }

    @Test
    @DisplayName("비밀번호만 맞으면 들어간다 — 운영자 계정을 미리 만들어 둘 필요가 없다")
    void password_alone_opens_the_console() throws Exception {
        // 운영자 롤 계정을 아무도 만들지 않은 상태에서 시작한다.
        jdbcTemplate.update("UPDATE users SET role = 'MEMBER' WHERE role = 'OPERATOR'");

        MvcResult res = login("console-test-pass");
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        String token = read(res, "$.data.accessToken");
        assertThat(token).isNotBlank();
        assertThat((String) read(res, "$.data.expiresAt")).isNotBlank();

        // 발급받은 토큰이 실제로 백오피스를 연다 — 로그인만 되고 열리지 않으면 의미가 없다.
        assertThat(mvc.perform(get("/api/v1/admin/dashboard/summary")
                .header("Authorization", "Bearer " + token)).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("콘솔 계정은 한 번만 만들어진다 — 로그인할 때마다 늘어나지 않는다")
    void console_account_is_created_once() throws Exception {
        jdbcTemplate.update("UPDATE users SET role = 'MEMBER' WHERE role = 'OPERATOR'");

        String first = read(login("console-test-pass"), "$.data.operatorId");
        String second = read(login("console-test-pass"), "$.data.operatorId");

        assertThat(second).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'OPERATOR'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("콘솔 계정은 회원이 아니다 — 공지 수신자와 회원 수에서 빠진다")
    void console_account_is_not_a_member() throws Exception {
        jdbcTemplate.update("UPDATE users SET role = 'MEMBER' WHERE role = 'OPERATOR'");
        String operatorId = read(login("console-test-pass"), "$.data.operatorId");

        // 팬아웃 수신자 조건(role='MEMBER')에 걸리지 않는다 — 안 읽는 알림함에 공지가 쌓이면
        // recipient_count 가 매번 실제와 어긋난다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ? AND role = 'MEMBER'",
                Integer.class, bytes(java.util.UUID.fromString(operatorId)))).isZero();
    }

    @Test
    @DisplayName("틀린 비밀번호는 401 이고 그 시도도 감사 로그에 남는다")
    void wrong_passcode_is_audited() throws Exception {
        long before = auditLogRepository.countByResult(AdminAuditLog.Result.DENIED);

        expectError(login("wrong-pass"), 401, "INVALID_PASSCODE");

        assertThat(auditLogRepository.countByResult(AdminAuditLog.Result.DENIED))
                .as("거부 급증이 우회 시도의 신호다 — 막고 끝내지 않는다")
                .isGreaterThan(before);
        assertThat(auditLogRepository.findAll())
                .anyMatch(l -> l.getAction() == AdminAction.ADMIN_LOGIN
                        && l.getResult() == AdminAuditLog.Result.DENIED);
    }

    @Test
    @DisplayName("로그인 경로만 공개다 — 나머지는 토큰 없이 열리지 않는다")
    void only_login_is_public() throws Exception {
        assertThat(mvc.perform(get("/api/v1/admin/dashboard/summary")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
    }
}
