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
        return mvc.perform(post("/api/v1/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("passcode", passcode)))).andReturn();
    }

    @Test
    @DisplayName("비밀번호가 맞으면 운영자 계정의 토큰을 준다 — 그 토큰으로 백오피스가 열린다")
    void login_issues_operator_token() throws Exception {
        Member op = member(uniq("console"));
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(op.id()));

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
