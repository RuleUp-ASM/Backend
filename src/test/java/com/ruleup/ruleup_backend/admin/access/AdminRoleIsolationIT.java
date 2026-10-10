package com.ruleup.ruleup_backend.admin.access;

import com.ruleup.ruleup_backend.security.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 관리자 서비스(role=admin) — <b>실제 배포와 같은 {@code admin} 프로필</b>로 컨텍스트를 띄워 본다.
 *
 * <ul>
 *   <li>허용된 운영자만 들어오고, 비허용·위조·만료·다른 앱 토큰은 컨트롤러에 닿지 않는다</li>
 *   <li>앱 사용자 JWT 는 운영자 계정의 것이라도 통하지 않는다</li>
 *   <li>공개 API 경로는 이 서비스에 없다</li>
 *   <li>상태 변경 요청은 콘솔 오리진에서 온 것만(CSRF)</li>
 *   <li>배치·스케줄러가 돌지 않는다</li>
 * </ul>
 * DB 는 운영과 같은 제한 계정 {@code ruleup_admin}(grants.sql)으로 붙는다.
 */
@AdminRoleTest
class AdminRoleIsolationIT {

    /** 관리자 요청이 쓴 테이블·권한이 관리자 DB 계정의 권한 목록 안에 있는지 — 넘으면 grants 를 갱신하라고 알린다. */
    @org.junit.jupiter.api.AfterAll
    static void adminQueriesStayWithinGrants() throws java.io.IOException {
        com.ruleup.ruleup_backend.admin.access.AdminSqlCapture.assertWithinGrants();
    }



    static final String ORIGIN = "https://admin.ruleup.co.kr";

    @Autowired WebApplicationContext wac;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwtProvider;
    @Value("${app.notification.queue.consumer-enabled}") boolean consumerEnabled;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder asOperator(MockHttpServletRequestBuilder request, String email) {
        return request.header(CloudflareAccessFilter.ASSERTION_HEADER, AdminRoleTest.ISSUER.issue(email));
    }

    @Test
    @DisplayName("허용된 운영자는 Access 토큰 하나로 들어오고, 사람마다 운영자 계정이 생겨 감사 로그가 그 사람을 가리킨다")
    void allowed_operator_gets_in_with_own_identity() throws Exception {
        assertThat(status(asOperator(get("/api/v1/admin/auth/session"), "ops@ruleup.co.kr"))).isEqualTo(200);
        assertThat(status(asOperator(get("/api/v1/admin/dashboard/summary"), "Ops@RuleUp.co.kr"))).isEqualTo(200);
        assertThat(status(asOperator(get("/api/v1/admin/dashboard/summary"), "second@ruleup.co.kr"))).isEqualTo(200);

        Integer accounts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE oauth_subject IN ('cf-access:ops@ruleup.co.kr','cf-access:second@ruleup.co.kr') AND role='OPERATOR'",
                Integer.class);
        assertThat(accounts).as("이메일마다 운영자 계정 하나 — 대소문자가 달라도 같은 사람").isEqualTo(2);

        Integer audited = jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_audit_logs a JOIN users u ON u.id = a.operator_id
                WHERE u.oauth_subject = 'cf-access:second@ruleup.co.kr' AND a.action = 'DASHBOARD_VIEW' AND a.result = 'ALLOWED'
                """, Integer.class);
        assertThat(audited).as("감사 로그의 수행자가 공용 계정이 아니라 그 사람").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Access 토큰이 없거나 위조·만료·다른 앱·다른 팀이면 401, 허용 목록 밖이면 403")
    void rejects_everything_else() throws Exception {
        String path = "/api/v1/admin/dashboard/summary";
        assertThat(status(get(path))).as("토큰 없음").isEqualTo(401);
        assertThat(status(get(path).header(CloudflareAccessFilter.ASSERTION_HEADER,
                new AccessTokens("kid-it").issue("ops@ruleup.co.kr")))).as("다른 키로 위조").isEqualTo(401);
        assertThat(status(get(path).header(CloudflareAccessFilter.ASSERTION_HEADER, AdminRoleTest.ISSUER.issue(
                Map.of("email", "ops@ruleup.co.kr"), AccessTokens.TEAM, AccessTokens.AUD, Instant.now().minusSeconds(300)))))
                .as("만료").isEqualTo(401);
        assertThat(status(get(path).header(CloudflareAccessFilter.ASSERTION_HEADER, AdminRoleTest.ISSUER.issue(
                Map.of("email", "ops@ruleup.co.kr"), AccessTokens.TEAM, "aud-admin-web", Instant.now().plusSeconds(300)))))
                .as("같은 팀의 다른 Access 앱").isEqualTo(401);
        assertThat(status(get(path).header(CloudflareAccessFilter.ASSERTION_HEADER, AdminRoleTest.ISSUER.issue(
                Map.of("email", "ops@ruleup.co.kr"), "https://evil.cloudflareaccess.com", AccessTokens.AUD, Instant.now().plusSeconds(300)))))
                .as("다른 팀").isEqualTo(401);
        assertThat(status(asOperator(get(path), "intruder@gmail.com"))).as("허용 목록 밖").isEqualTo(403);

        // 평문 이메일 헤더는 읽지 않는다
        assertThat(status(get(path).header("Cf-Access-Authenticated-User-Email", "ops@ruleup.co.kr"))).isEqualTo(401);
    }

    @Test
    @DisplayName("앱 사용자 JWT 는 운영자 계정의 것이라도 통하지 않는다 — 비밀번호 진입도 닫혀 있다")
    void app_jwt_and_passcode_are_useless() throws Exception {
        mvc.perform(asOperator(get("/api/v1/admin/auth/session"), "ops@ruleup.co.kr"));
        UUID operatorId = UUID.fromString(jdbc.queryForObject(
                "SELECT BIN_TO_UUID(id) FROM users WHERE oauth_subject = 'cf-access:ops@ruleup.co.kr'", String.class));

        assertThat(status(get("/api/v1/admin/dashboard/summary")
                .header("Authorization", "Bearer " + jwtProvider.issueAccessToken(operatorId)))).isEqualTo(401);

        assertThat(status(asOperator(post("/api/v1/admin/auth/login"), "ops@ruleup.co.kr")
                .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"passcode\":\"anything\"}"))).as("Access 를 통과해도 앱 토큰은 내주지 않는다").isEqualTo(401);
    }

    @Test
    @DisplayName("공개 API 경로는 관리자 서비스에 없다 — 운영자 신원으로도 열리지 않는다")
    void public_api_is_absent() throws Exception {
        assertThat(status(asOperator(get("/api/v1/categories"), "ops@ruleup.co.kr"))).isIn(403, 404);
        assertThat(status(asOperator(get("/api/v1/me"), "ops@ruleup.co.kr"))).isIn(403, 404);
        assertThat(status(asOperator(get("/v3/api-docs"), "ops@ruleup.co.kr"))).isIn(403, 404);
        assertThat(status(get("/api/v1/categories"))).isEqualTo(401);
    }

    @Test
    @DisplayName("상태 변경 요청은 콘솔 오리진에서 온 것만 — Access 쿠키를 노린 CSRF 차단")
    void csrf_origin_check() throws Exception {
        String path = "/api/v1/admin/auth/logout";
        assertThat(status(asOperator(post(path), "ops@ruleup.co.kr"))).as("Origin 없음").isEqualTo(403);
        assertThat(status(asOperator(post(path), "ops@ruleup.co.kr").header("Origin", "https://evil.example")))
                .as("다른 사이트").isEqualTo(403);
        assertThat(status(asOperator(post(path), "ops@ruleup.co.kr").header("Origin", ORIGIN)
                .header("Sec-Fetch-Site", "cross-site"))).as("브라우저가 교차 사이트라고 표시").isEqualTo(403);
        assertThat(status(asOperator(post(path), "ops@ruleup.co.kr").header("Origin", ORIGIN)
                .header("Sec-Fetch-Site", "same-origin"))).isEqualTo(200);
    }

    @Test
    @DisplayName("관리자 DB 계정은 DDL 도, 권한 밖 테이블 읽기도 못 한다")
    void admin_db_account_is_least_privilege() {
        assertThat(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)).startsWith("ruleup_admin@");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class))
                .as("세션 토큰 테이블은 관리자 기능이 쓰지 않는다").isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.execute("CREATE TABLE admin_probe (id INT)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.execute("ALTER TABLE users ADD COLUMN probe INT"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("DELETE FROM sanctions"))
                .as("제재는 이력이라 지우지 못한다").isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @DisplayName("관리자 서비스에서는 배치 스케줄러·SQS 컨슈머가 돌지 않는다")
    void no_background_work() {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .as("@EnableScheduling 이 꺼져 있어야 배치가 공개 API 와 겹쳐 돌지 않는다").isEmpty();
        assertThat(consumerEnabled).isFalse();
    }
}
