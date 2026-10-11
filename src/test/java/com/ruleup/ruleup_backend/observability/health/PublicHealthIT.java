package com.ruleup.ruleup_backend.observability.health;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 외부 점검이 기대하는 계약 — 토큰 없이 200, 고정 본문, 캐시 금지. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PublicHealthIT {

    @Autowired WebApplicationContext wac;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("토큰 없이 200 과 고정 본문 ruleup-ok 를 돌려주고, CDN 이 담아 두지 못하게 no-store 다")
    void publicHealthWithoutToken() throws Exception {
        mvc.perform(get(PublicHealthController.PATH))
                .andExpect(status().isOk())
                .andExpect(content().string(PublicHealthController.BODY))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
