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
 * 전환 기간 — 공개 API(role=api)에 {@code legacy-public-admin=true} 를 주면 기존 콘솔(비밀번호 진입 + 앱 JWT)이
 * 그대로 동작한다. 새 관리자 서비스가 준비되기 전에 이 코드를 배포해도 운영 콘솔이 끊기지 않는다는 보장이다.
 */
@SpringBootTest(properties = {
        "app.runtime.role=api",
        "app.runtime.legacy-public-admin=true",
        "app.admin.passcode=legacy-pass",
})
@Import(TestcontainersConfiguration.class)
class LegacyPublicAdminIT {

    @Autowired WebApplicationContext wac;

    @Test
    @DisplayName("전환 플래그가 켜진 공개 API 는 기존 콘솔 로그인·조회를 그대로 서빙한다")
    void legacy_console_keeps_working() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"passcode\":\"legacy-pass\"}")).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        String token = com.jayway.jsonpath.JsonPath.read(login.getResponse().getContentAsString(), "$.data.accessToken");

        assertThat(mvc.perform(get("/api/v1/admin/dashboard/summary").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
