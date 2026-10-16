package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsProjectionService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** 같은 방의 통계 재계산 요청이 몰리면, 아직 시작하지 않은 재계산 한 번으로 합친다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeStatsCoalescingIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager txManager;
    @MockitoSpyBean ChallengeStatsProjectionService projection;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build(); }

    @Test
    @DisplayName("재계산이 도는 동안 들어온 요청 20건은 다음 재계산 한 번으로 합쳐지고 그 한 번이 마지막 가입까지 센다")
    void burstForOneChallengeCoalesces() throws Exception {
        Member owner = member(uniq("coal-owner"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");

        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(inv -> {
            if (started.getCount() > 0) { started.countDown(); release.await(10, TimeUnit.SECONDS); }
            return inv.callRealMethod();
        }).when(projection).refresh(eq(challengeId));

        publish(challengeId);                                    // 첫 재계산 — 붙잡아 둔다
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 20; i++) {                           // 그동안 가입 20건이 커밋된다
            insertActiveMembership(challengeId, member(uniq("coal-m")).id(), "MEMBER");
            publish(challengeId);
        }
        release.countDown();

        verify(projection, timeout(10_000).times(2)).refresh(challengeId);
        Thread.sleep(500);
        verify(projection, times(2)).refresh(challengeId);
        Integer count = jdbcTemplate.queryForObject("SELECT participant_count FROM challenges WHERE id=?", Integer.class, bytes(challengeId));
        assertThat(count).isEqualTo(21);
    }

    private void publish(UUID challengeId) {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                events.publishEvent(ChallengeStatsRefreshRequested.of(challengeId, "JOIN")));
    }
}
