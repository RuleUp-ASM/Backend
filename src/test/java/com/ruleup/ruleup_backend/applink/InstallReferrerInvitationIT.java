package com.ruleup.ruleup_backend.applink;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.challenge.domain.InvitationTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 초대 링크로 설치·가입한 사람의 받은 초대(09-28 결정, QA NAV-06) — 가입 요청의 {@code inviteLink}.
 * 가입 화면은 일반 가입과 같으므로 받은 초대가 알림함에 남아야 초대 화면으로 돌아갈 수 있다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InstallReferrerInvitationIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired com.ruleup.ruleup_backend.watcher.infra.Tokens watcherTokens;
    @Autowired com.ruleup.ruleup_backend.watcher.repository.WatcherInvitationRepository watcherInvitations;

    @Value("${app.app-links.base-url}")
    String linkBaseUrl;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private UUID signupWith(String tag, String inviteLink) throws Exception {
        Map<String, Object> body = preparedSignup(uniq(tag), "설치" + seq());
        body.put("inviteLink", inviteLink);
        MvcResult res = postJson("/api/v1/auth/signup", body);
        assertThat(res.getResponse().getStatus()).as("초대가 잘못돼도 가입은 막지 않는다").isEqualTo(200);
        return UUID.fromString(read(res, "$.data.user.id"));
    }

    private List<Map<String, Object>> invitationNotices(UUID userId) {
        return jdbc().queryForList("SELECT title, body, deeplink FROM notifications " +
                "WHERE user_id = ? AND type = 'INVITATION_RECEIVED'", bytes(userId));
    }

    private String challengeInvitation(UUID challengeId, UUID inviterId, int expiresInDays) {
        String token = InvitationTokens.generate();
        jdbc().update("INSERT INTO challenge_invitations (id, challenge_id, inviter_id, token_hash, expires_at) " +
                        "VALUES (?, ?, ?, ?, DATE_ADD(NOW(3), INTERVAL ? DAY))",
                bytes(UUID.randomUUID()), bytes(challengeId), bytes(inviterId), InvitationTokens.hash(token), expiresInDays);
        return token;
    }

    @Test
    @DisplayName("챌린지 초대 링크 — 알림함에 초대가 남고 누르면 초대 화면(ruleup://c/{token})으로 간다")
    void challengeInvitation() throws Exception {
        Member owner = member(uniq("ref-owner"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        String token = challengeInvitation(challengeId, owner.id(), 7);
        String title = jdbc().queryForObject("SELECT title FROM challenges WHERE id = ?", String.class, bytes(challengeId));

        UUID me = signupWith("ref-c", linkBaseUrl + "/c/" + token);

        assertThat(invitationNotices(me)).singleElement().satisfies(n -> {
            assertThat(n.get("deeplink")).isEqualTo("ruleup://c/" + token);
            assertThat((String) n.get("body")).startsWith("[" + title + "]");
        });
    }

    @Test
    @DisplayName("감시자 초대 링크 — 요청한 사람 이름으로 알림이 남는다")
    void watcherInvitation() throws Exception {
        Member owner = member(uniq("ref-watch"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        String token = watcherTokens.issue(challengeId, owner.id(), Instant.now().plus(Duration.ofDays(7)));
        watcherInvitations.save(com.ruleup.ruleup_backend.watcher.domain.WatcherInvitation.issue(challengeId, owner.id(),
                com.ruleup.ruleup_backend.watcher.infra.WatcherHashes.sha256Hex(token), Instant.now()));

        UUID me = signupWith("ref-w", "ruleup://w/" + token);

        assertThat(invitationNotices(me)).singleElement()
                .satisfies(n -> assertThat(n.get("deeplink")).isEqualTo("ruleup://w/" + token));
    }

    @Test
    @DisplayName("친구 초대 링크 — inviteCode 를 따로 보내지 않아도 초대 기록이 연동되고 알림이 남는다")
    void friendInvitation() throws Exception {
        Member inviter = member(uniq("ref-friend"));
        String code = ("F" + Long.toString(System.nanoTime() % 100000, 36).toUpperCase() + "XXXXX").substring(0, 6);
        jdbc().update("INSERT INTO InviteCode (id, userId, code) VALUES (?, ?, ?)",
                bytes(UUID.randomUUID()), bytes(inviter.id()), code);

        UUID me = signupWith("ref-f", linkBaseUrl + "/inv/" + code);

        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM InvitationSignup WHERE inviterUserId = ? AND inviteeUserId = ?",
                Integer.class, bytes(inviter.id()), bytes(me))).isEqualTo(1);
        assertThat(invitationNotices(me)).singleElement()
                .satisfies(n -> assertThat(n.get("deeplink")).isEqualTo("ruleup://inv/" + code));
    }

    @Test
    @DisplayName("만료·위조·남의 도메인 링크는 조용히 무시한다 — 가입은 그대로 된다")
    void invalidLinksAreIgnored() throws Exception {
        Member owner = member(uniq("ref-bad"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        String expired = challengeInvitation(challengeId, owner.id(), -1);

        assertThat(invitationNotices(signupWith("ref-x1", linkBaseUrl + "/c/" + expired))).isEmpty();
        assertThat(invitationNotices(signupWith("ref-x2", linkBaseUrl + "/c/forged"))).isEmpty();
        assertThat(invitationNotices(signupWith("ref-x3", "https://evil.example.com/c/" + expired))).isEmpty();
    }
}
