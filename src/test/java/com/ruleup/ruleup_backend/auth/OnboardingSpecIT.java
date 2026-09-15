package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.moderation.ModerationResult;
import com.ruleup.ruleup_backend.moderation.UserModerationService;
import com.ruleup.ruleup_backend.user.DormancyProcessor;
import com.ruleup.ruleup_backend.user.UserActivityService;
import com.ruleup.ruleup_backend.verification.service.DeviceSyncPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OnboardingSpecIT extends ChallengeApiSupport {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired UserModerationService moderation;
    @Autowired DormancyProcessor dormancy;
    @Autowired UserActivityService activity;
    @Autowired com.ruleup.ruleup_backend.config.AppProperties properties;
    @MockitoBean ContentModerationClient client;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(client.moderateNickname(anyString())).thenReturn(ModerationResult.UNAVAILABLE);
        when(client.moderateImage(anyString())).thenReturn(ModerationResult.UNAVAILABLE);
    }
    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbc; }

    @Test void signupTokenConsumptionIsSharedAcrossInstances() {
        var first = new SignupTokenStore(properties, jdbc);
        var second = new SignupTokenStore(properties, jdbc);
        String jti = java.util.UUID.randomUUID().toString();
        assertThat(first.consume(jti)).isTrue();
        assertThat(second.isUsed(jti)).isTrue();
        assertThat(second.consume(jti)).isFalse();
    }

    @Test void signupReturnsTheSynchronousModerationResult() throws Exception {
        when(client.moderateNickname(anyString())).thenReturn(ModerationResult.APPROVED);
        var result = signup(uniq("sync-moderation"), "동기검수" + uniq("").substring(0, 5));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) read(result, "$.data.user.nicknameStatus")).isEqualTo("APPROVED");
    }

    @Test void changedNicknameDiscardsStaleReviewWithoutHoldingTheUserLock() throws Exception {
        var user = member(uniq("review-race"));
        jdbc.update("UPDATE users SET nickname='before',nickname_status='PENDING',profile_image_key='https://image.test/new'," +
                "profile_image_status='PENDING' WHERE id=?", bytes(user.id()));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(client.moderateNickname("before")).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return ModerationResult.REJECTED;
        });
        when(client.moderateImage(anyString())).thenReturn(ModerationResult.APPROVED);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var review = executor.submit(() -> moderation.moderate(user.id()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.submit(() -> jdbc.update("UPDATE users SET nickname='BEFORE' WHERE id=?", bytes(user.id())))
                        .get(3, TimeUnit.SECONDS)).isEqualTo(1);
            } finally { release.countDown(); }
            review.get(5, TimeUnit.SECONDS);
        }
        var row = jdbc.queryForMap("SELECT nickname_status,profile_image_status FROM users WHERE id=?", bytes(user.id()));
        assertThat(row.get("nickname_status")).isEqualTo("PENDING");
        assertThat(row.get("profile_image_status")).isEqualTo("APPROVED");
    }

    @Test void databaseSyncPolicyRequiresKnownFactsAndUsesPriorityAndFallback() {
        transactions.executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO device_sync_policies VALUES(UNHEX('01950000000070008000000000000004'),200," +
                    "'{\"platform\":\"ANDROID\",\"minRamMb\":8000}',900,TRUE)");
            var policy = new DeviceSyncPolicyService(jdbc);
            assertThat(policy.resolve(null)).isEqualTo(1800);
            assertThat(policy.resolve(Map.of("platform", "ANDROID"))).isEqualTo(1800);
            assertThat(policy.resolve(Map.of("platform", "ANDROID", "ramMb", 8192, "lowRam", true))).isEqualTo(900);
            assertThat(policy.resolve(Map.of("lowRam", true))).isEqualTo(3600);
            jdbc.update("DELETE FROM device_sync_policies WHERE priority=0");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DeviceSyncPolicyService(jdbc).validateFallback())
                    .isInstanceOf(IllegalStateException.class);
            tx.setRollbackOnly();
        });
    }

    @Test void dormancyWaitsForEveryNoticeAndUsesAnExemptLeave() throws Exception {
        var user = member(uniq("dormancy"));
        var room = insertChallenge(user.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(room, user.id(), "OWNER");
        jdbc.update("UPDATE user_activity SET last_active_on=DATE_SUB(CURDATE(),INTERVAL 365 DAY),notified_stage='NONE' WHERE user_id=?", bytes(user.id()));
        assertThat(dormancy.advance(user.id())).isTrue(); // D7
        assertThat(dormancy.advance(user.id())).isFalse();
        jdbc.update("UPDATE notifications SET created_at=DATE_SUB(NOW(),INTERVAL 7 DAY) WHERE user_id=?", bytes(user.id()));
        assertThat(dormancy.advance(user.id())).isTrue(); // D1
        assertThat(dormancy.advance(user.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM challenge_members WHERE challenge_id=?", String.class, bytes(room))).isEqualTo("ACTIVE");
        jdbc.update("UPDATE notifications SET created_at=DATE_SUB(NOW(),INTERVAL 2 DAY) WHERE user_id=?", bytes(user.id()));
        assertThat(dormancy.advance(user.id())).isTrue(); // D30
        assertThat(jdbc.queryForObject("SELECT leave_reason FROM challenge_members WHERE challenge_id=?", String.class, bytes(room))).isEqualTo("DORMANT");
        assertThat(jdbc.queryForObject("SELECT rejoin_available_at FROM challenge_members WHERE challenge_id=?", Object.class, bytes(room))).isNull();
        assertThat(dormancy.advance(user.id())).isTrue(); // Y30
        assertThat(dormancy.advance(user.id())).isFalse();
        jdbc.update("UPDATE notifications SET created_at=DATE_SUB(NOW(),INTERVAL 31 DAY) WHERE user_id=?", bytes(user.id()));
        assertThat(dormancy.advance(user.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, bytes(user.id()))).isEqualTo("WITHDRAWN");
    }

    @Test void newActivityResetsNoticeProgressAndDoesNotRewriteTheAccount() throws Exception {
        var user = member(uniq("activity"));
        jdbc.update("UPDATE user_activity SET last_active_on=DATE_SUB(CURDATE(),INTERVAL 24 DAY),notified_stage='D7' WHERE user_id=?", bytes(user.id()));
        Object updated = jdbc.queryForObject("SELECT updated_at FROM users WHERE id=?", Object.class, bytes(user.id()));
        activity.touch(user.id());
        activity.touch(user.id());
        assertThat(jdbc.queryForObject("SELECT notified_stage FROM user_activity WHERE user_id=?", String.class, bytes(user.id()))).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM users WHERE id=?", Object.class, bytes(user.id()))).isEqualTo(updated);
    }
}
