package com.ruleup.ruleup_backend.perf;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.moderation.ModerationResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionImplementor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 외부 호출(LLM 심사 등)을 기다리는 동안 요청이 DB 커넥션을 쥐고 있지 않은지 본다.
 * 부하테스트(2026-10, DAU 2만 시드): 가입 60건이 동시에 몰리자 닉네임 심사(Gemini ~1초) 내내 커넥션이 묶여
 * 풀 15개가 다 찼고, RDS CPU 16% 인데도 다른 모든 API 가 30초 대기 뒤 500 이 됐다 — open-in-view 가 켜져 있으면
 * 요청 전체가 EntityManager 하나를 쓰고, 그 EntityManager 가 처음 잡은 커넥션을 요청 끝까지 놓지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExternalCallConnectionIT extends AuthApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired EntityManagerFactory emf;
    @MockitoBean ContentModerationClient moderationClient;
    private MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build(); }

    @Test
    @DisplayName("가입 직후 닉네임 심사를 기다리는 동안 DB 커넥션을 쥐지 않는다")
    void signupModerationDoesNotHoldConnection() throws Exception {
        AtomicReference<String> during = new AtomicReference<>("not-called");
        when(moderationClient.moderateNickname(anyString())).thenAnswer(inv -> {
            during.set(connectionState());
            return ModerationResult.APPROVED;
        });

        String tag = uniq("osiv");
        var res = signup(tag, "커넥션" + seq());

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(during.get()).isEqualTo("no-connection");
    }

    /** 이 스레드에 묶인 EntityManager 가 물리 커넥션을 쥐고 있는지. */
    private String connectionState() {
        var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(emf);
        if (holder == null) return "no-connection";
        EntityManager em = holder.getEntityManager();
        boolean connected = em.unwrap(SessionImplementor.class).getJdbcCoordinator().getLogicalConnection().isPhysicallyConnected();
        return connected ? "holding-connection" : "no-connection";
    }
}
