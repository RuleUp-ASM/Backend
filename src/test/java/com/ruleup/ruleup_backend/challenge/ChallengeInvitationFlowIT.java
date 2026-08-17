package com.ruleup.ruleup_backend.challenge;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 초대 링크 조회 → 수락 흐름.
 *
 * <p>지금까지 방장은 초대 토큰을 <b>발급만</b> 할 수 있었다 — 받은 사람이 그 링크로 들어올 경로가
 * 아예 없어서 비공개 방은 사실상 아무도 새로 들어올 수 없었다. 이 테스트가 두 엔드포인트의 계약을 고정한다.
 *
 * <p>핵심은 "수락 = 가입"이라는 점이다. 비공개 검증만 토큰으로 대체될 뿐 재입장 대기·동시 3개·정원·
 * 티어 게이트는 일반 가입과 똑같이 걸린다. 조회의 {@code blockReason} 과 수락의 {@code error.reason} 이
 * 같은 enum 을 쓰는 것도 그래서다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeInvitationFlowIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("초대 링크를 조회하면 어떤 방인지와 수락 가능 여부를 함께 준다")
    void previewShowsChallengeAndJoinability() throws Exception {
        Member owner = member(uniq("inv-owner"));
        Member guest = member(uniq("inv-guest"));
        UUID challengeId = privateChallenge(owner);
        String token = issueToken(owner, challengeId);

        JsonNode data = data(getAuth("/api/v1/challenges/invitations/" + token, guest.token()));

        assertThat(data.path("challenge").path("challengeId").asText()).isEqualTo(challengeId.toString());
        assertThat(data.path("challenge").path("title").asText()).isEqualTo("테스트 챌린지");
        assertThat(data.path("challenge").path("capacity").asInt()).isEqualTo(50);
        assertThat(data.path("challenge").path("participantCount").asInt()).isEqualTo(1);
        assertThat(data.path("inviterNickname").asText()).isNotBlank();
        assertThat(data.path("joinable").asBoolean()).isTrue();
        assertThat(data.path("blockReason").isNull()).isTrue();
        assertThat(data.path("expiresAt").asText()).isNotBlank();
        assertThat(data.path("invitationId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("정원이 찬 방은 조회 단계에서 joinable=false + blockReason=FULL 로 미리 알려준다")
    void previewReportsBlockReasonBeforeAccepting() throws Exception {
        Member owner = member(uniq("inv-full-owner"));
        Member guest = member(uniq("inv-full-guest"));
        UUID challengeId = privateChallenge(owner);
        String token = issueToken(owner, challengeId);
        jdbcTemplate.update("UPDATE challenges SET capacity=1 WHERE id=?", bytes(challengeId));

        JsonNode data = data(getAuth("/api/v1/challenges/invitations/" + token, guest.token()));

        assertThat(data.path("joinable").asBoolean()).isFalse();
        assertThat(data.path("blockReason").asText()).isEqualTo("FULL");
    }

    @Test
    @DisplayName("수락하면 비공개 방에 가입되고 토큰은 그 자리에서 소모된다")
    void acceptJoinsPrivateChallengeAndBurnsToken() throws Exception {
        Member owner = member(uniq("inv-accept-owner"));
        Member guest = member(uniq("inv-accept-guest"));
        UUID challengeId = privateChallenge(owner);
        String token = issueToken(owner, challengeId);

        MvcResult accepted = accept(token, guest.token());
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) read(accepted, "$.data.joined")).isTrue();
        assertThat((String) read(accepted, "$.data.countFromCycle")).isNotBlank();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM challenge_members WHERE challenge_id=? AND user_id=?",
                String.class, bytes(challengeId), bytes(guest.id()))).isEqualTo("ACTIVE");

        // 1회성 — 같은 링크로 다른 사람이 또 들어올 수 없다
        Member second = member(uniq("inv-accept-second"));
        expectError(accept(token, second.token()), 410, "INVITATION_EXPIRED");
    }

    @Test
    @DisplayName("만료된 링크와 없는 링크는 각각 410·404로 갈린다")
    void expiredAndUnknownTokensAreDistinguished() throws Exception {
        Member owner = member(uniq("inv-exp-owner"));
        Member guest = member(uniq("inv-exp-guest"));
        UUID challengeId = privateChallenge(owner);
        String token = issueToken(owner, challengeId);
        jdbcTemplate.update("UPDATE challenge_invitations SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 DAY)");

        expectError(getAuth("/api/v1/challenges/invitations/" + token, guest.token()),
                410, "INVITATION_EXPIRED");
        expectError(accept(token, guest.token()), 410, "INVITATION_EXPIRED");
        expectError(getAuth("/api/v1/challenges/invitations/nope-not-a-real-token", guest.token()),
                404, "INVITATION_NOT_FOUND");
    }

    @Test
    @DisplayName("초대장이 있어도 가입 게이트는 그대로 걸린다 — 정원이 차면 409 JOIN_BLOCKED")
    void acceptStillRunsTheJoinGates() throws Exception {
        Member owner = member(uniq("inv-gate-owner"));
        Member guest = member(uniq("inv-gate-guest"));
        UUID challengeId = privateChallenge(owner);
        String token = issueToken(owner, challengeId);
        jdbcTemplate.update("UPDATE challenges SET capacity=1 WHERE id=?", bytes(challengeId));

        MvcResult blocked = accept(token, guest.token());
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) read(blocked, "$.error.code")).isEqualTo("JOIN_BLOCKED");
        assertThat((String) read(blocked, "$.error.reason")).isEqualTo("FULL");
        // 게이트에 막혔으면 토큰은 살아 있어야 한다 — 정원이 빈 뒤 같은 링크로 들어올 수 있어야 하므로
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM challenge_invitations WHERE used_at IS NULL", Integer.class)).isPositive();
    }

    @Test
    @DisplayName("비공개 방은 초대 없이 직접 가입할 수 없다 — 초대 경로만 뚫려 있다")
    void privateChallengeStillRejectsDirectJoin() throws Exception {
        Member owner = member(uniq("inv-direct-owner"));
        Member guest = member(uniq("inv-direct-guest"));
        UUID challengeId = privateChallenge(owner);

        MvcResult direct = mvc.perform(post("/api/v1/challenges/" + challengeId + "/members")
                .header("Authorization", "Bearer " + guest.token())).andReturn();
        assertThat(direct.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) read(direct, "$.error.reason")).isEqualTo("PRIVATE_INVITE_ONLY");
    }

    // ===== 헬퍼 =====

    private UUID privateChallenge(Member owner) {
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        jdbcTemplate.update("UPDATE challenges SET visibility='PRIVATE' WHERE id=?", bytes(challengeId));
        return challengeId;
    }

    private String issueToken(Member owner, UUID challengeId) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/challenges/" + challengeId + "/invitations")
                .header("Authorization", "Bearer " + owner.token())).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        return read(res, "$.data.token");
    }

    private MvcResult accept(String token, String accessToken) throws Exception {
        return mvc.perform(post("/api/v1/challenges/invitations/" + token + "/accept")
                .header("Authorization", "Bearer " + accessToken)).andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return OM.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
