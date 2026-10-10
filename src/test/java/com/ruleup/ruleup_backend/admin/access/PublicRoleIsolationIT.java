package com.ruleup.ruleup_backend.admin.access;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.security.JwtProvider;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 공개 API 서비스(role=api) — 관리자 경로가 <b>존재하지 않는다</b>.
 *
 * <p>운영자 계정의 앱 토큰을 들고 와도, 비밀번호를 알아도 관리자 기능에 닿지 않는다.
 * 차단 규칙이 아니라 매핑 부재이므로 ALB·WAF 설정이 빠져도 같은 결과다.
 */
@SpringBootTest(properties = {
        "app.runtime.role=api",
        "app.admin.passcode=public-role-pass",
})
@Import(TestcontainersConfiguration.class)
class PublicRoleIsolationIT {

    @Autowired WebApplicationContext wac;
    @Autowired JwtProvider jwtProvider;
    @Autowired UserRepository userRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("운영자 토큰으로도 관리자 경로는 404, 비밀번호 로그인도 404")
    void admin_paths_do_not_exist() throws Exception {
        User operator = User.create(OAuthProvider.KAKAO, "public-role-op-" + UUID.randomUUID(), null,
                "op" + (int) (Math.random() * 1_000_000), null, List.of());
        operator.approveNickname();
        operator.grantRole(UserRole.OPERATOR);
        String token = jwtProvider.issueAccessToken(userRepository.saveAndFlush(operator).getId());

        for (String path : List.of("/api/v1/admin/dashboard/summary", "/api/v1/admin/auth/session",
                "/api/v1/admin/reports", "/api/v1/admin/inquiries", "/api/v1/admin/sanctions")) {
            assertThat(status(get(path).header("Authorization", "Bearer " + token))).as(path).isEqualTo(404);
        }
        assertThat(status(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"passcode\":\"public-role-pass\"}"))).isEqualTo(404);
        assertThat(status(post("/api/v1/admin/notices").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))).isEqualTo(404);

        // 공개 API 는 그대로 산다
        assertThat(status(get("/api/v1/categories"))).isEqualTo(200);
    }
}
