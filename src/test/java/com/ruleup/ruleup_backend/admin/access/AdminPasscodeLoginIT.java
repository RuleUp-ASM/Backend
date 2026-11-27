package com.ruleup.ruleup_backend.admin.access;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 관리자 서비스(role=admin)를 비밀번호 진입으로 띄운 구성 — prod 의 관리자 Lambda 가 이렇게 뜬다
 * ({@code ADMIN_LOGIN=passcode}, infra/admin-lambda). 기본값(access)에서 비밀번호가 닫혀 있다는 보장은
 * {@link AdminRoleIsolationIT} 가 맡는다.
 */
@SpringBootTest(properties = {
        "app.runtime.role=admin",
        "app.admin.login=passcode",
        "app.admin.passcode=lambda-pass",
        "app.scheduling.enabled=false",
        "app.scheduling.lock.enabled=false",
        "app.notification.queue.consumer-enabled=false",
})
@Import(TestcontainersConfiguration.class)
class AdminPasscodeLoginIT {

    @Autowired WebApplicationContext wac;

    @Test
    @DisplayName("비밀번호 진입으로 띄운 관리자 서비스는 비밀번호로 토큰을 받고, 그 토큰으로 관리자 API 를 쓴다")
    void passcode_opens_admin_api() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        assertThat(mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"passcode\":\"wrong\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/admin/dashboard/summary")).andReturn().getResponse().getStatus())
                .as("토큰 없이는 못 들어온다").isEqualTo(401);

        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"passcode\":\"lambda-pass\"}")).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        String token = com.jayway.jsonpath.JsonPath.read(login.getResponse().getContentAsString(), "$.data.accessToken");

        assertThat(mvc.perform(get("/api/v1/admin/dashboard/summary").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).as("공개 API 는 이 서비스에 매핑이 없다").isIn(403, 404);
    }
}
