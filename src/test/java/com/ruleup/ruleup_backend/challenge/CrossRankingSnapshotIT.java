package com.ruleup.ruleup_backend.challenge;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.room.service.CrossRankingSnapshotService;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CrossRankingSnapshotIT extends ChallengeApiSupport {
    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CrossRankingSnapshotService snapshotService;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("외부 랭킹은 배치 스냅샷으로 고정되고 미등재 내 챌린지도 상태를 돌려준다")
    void rankingRemainsStableUntilNextSnapshot() throws Exception {
        Member owner = member(uniq("cross-owner"));
        UUID ranked = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(ranked, owner.id(), "OWNER");
        setDays(ranked, owner.id(), 45, 5);

        UUID unranked = insertChallenge(owner.id(), "STUDY", "ACTIVE", "GROUP");
        insertActiveMembership(unranked, owner.id(), "OWNER");
        setDays(unranked, owner.id(), 5, 4);

        snapshotService.refresh();
        JsonNode first = ranking(owner, ranked);
        JsonNode rankedItem = item(first.path("items"), ranked);
        assertThat(rankedItem.path("memberCount").asInt()).isEqualTo(1);
        assertThat(rankedItem.path("totalCount").asInt()).isEqualTo(50);
        assertThat(rankedItem.path("successRate").decimalValue()).isEqualByComparingTo("0.9000");
        String updatedAt = first.path("updatedAt").asText();

        setDays(ranked, owner.id(), 0, 50);
        JsonNode unchanged = ranking(owner, ranked);
        assertThat(item(unchanged.path("items"), ranked).path("successRate").decimalValue())
                .isEqualByComparingTo("0.9000");
        assertThat(unchanged.path("updatedAt").asText()).isEqualTo(updatedAt);

        JsonNode mine = ranking(owner, unranked).path("myChallenge");
        assertThat(mine.path("challengeId").asText()).isEqualTo(unranked.toString());
        assertThat(mine.path("ranked").asBoolean()).isFalse();
        assertThat(mine.get("rank").isNull()).isTrue();
        assertThat(mine.path("totalCount").asInt()).isEqualTo(9);

        snapshotService.refresh();
        JsonNode refreshed = ranking(owner, ranked);
        assertThat(item(refreshed.path("items"), ranked).path("successRate").decimalValue())
                .isEqualByComparingTo("0.0000");
    }

    private JsonNode ranking(Member viewer, UUID challengeId) throws Exception {
        MvcResult result = getAuth("/api/v1/rankings/challenges?mode=GROUP&size=50&challengeId=" + challengeId,
                viewer.token());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return OM.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode item(JsonNode items, UUID challengeId) {
        for (JsonNode item : items)
            if (challengeId.toString().equals(item.path("challengeId").asText())) return item;
        throw new AssertionError("랭킹에 " + challengeId + " 가 없다");
    }

    private void setDays(UUID challengeId, UUID userId, int success, int fail) {
        jdbcTemplate.update("UPDATE challenge_members SET success_days=?,fail_days=? " +
                        "WHERE challenge_id=? AND user_id=?", success, fail, bytes(challengeId), bytes(userId));
    }
}
