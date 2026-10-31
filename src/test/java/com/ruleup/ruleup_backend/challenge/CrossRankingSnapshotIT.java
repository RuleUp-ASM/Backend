package com.ruleup.ruleup_backend.challenge;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.perf.SqlRecorder;
import com.ruleup.ruleup_backend.room.service.CrossRankingSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@Import({TestcontainersConfiguration.class, SqlRecorder.Config.class})
class CrossRankingSnapshotIT extends ChallengeApiSupport {
    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CrossRankingSnapshotService snapshotService;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ChallengeMemberService memberService;
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
        // 목록 첫 페이지가 아니라 myChallenge 로 본다 — 같은 DB 의 다른 테스트가 남긴 방(시드 데모 방 등)이
        // 더 높은 성공률이면 이 방은 첫 50개 밖으로 밀린다. 방 수와 무관하게 그 방의 스냅숏 값만 확인한다.
        JsonNode first = ranking(owner, ranked);
        JsonNode rankedMine = first.path("myChallenge");
        assertThat(rankedMine.path("ranked").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT member_count FROM challenge_cross_ranking_snapshot WHERE challenge_id=?",
                Integer.class, bytes(ranked))).isEqualTo(1);
        assertThat(rankedMine.path("totalCount").asInt()).isEqualTo(50);
        assertThat(rankedMine.path("successRate").decimalValue()).isEqualByComparingTo("0.9000");
        String updatedAt = first.path("updatedAt").asText();

        setDays(ranked, owner.id(), 0, 50);
        JsonNode unchanged = ranking(owner, ranked);
        assertThat(unchanged.path("myChallenge").path("successRate").decimalValue())
                .isEqualByComparingTo("0.9000");
        assertThat(unchanged.path("updatedAt").asText()).isEqualTo(updatedAt);

        JsonNode mine = ranking(owner, unranked).path("myChallenge");
        assertThat(mine.path("challengeId").asText()).isEqualTo(unranked.toString());
        assertThat(mine.path("ranked").asBoolean()).isFalse();
        assertThat(mine.get("rank").isNull()).isTrue();
        assertThat(mine.path("totalCount").asInt()).isEqualTo(9);

        snapshotService.refresh();
        JsonNode refreshed = ranking(owner, ranked);
        assertThat(refreshed.path("myChallenge").path("successRate").decimalValue())
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("멤버가 나가면 그 방 집계를 고치고 순위를 방 수와 상관없이 몇 문장으로 다시 매긴다")
    void departureReranksInConstantStatements() throws Exception {
        Member owner = member(uniq("cross-dep"));
        Member leaver = member(uniq("cross-leaver"));
        UUID a = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(a, owner.id(), "OWNER");
        insertActiveMembership(a, leaver.id(), "MEMBER");
        setDays(a, owner.id(), 45, 5);
        setDays(a, leaver.id(), 50, 0);                       // a: 95/100 = 0.95
        UUID b = insertChallenge(owner.id(), "STUDY", "ACTIVE", "GROUP");
        insertActiveMembership(b, owner.id(), "OWNER");
        setDays(b, owner.id(), 46, 4);                        // b: 0.92
        UUID c = insertChallenge(owner.id(), "STUDY", "ACTIVE", "GROUP");
        insertActiveMembership(c, owner.id(), "OWNER");
        setDays(c, owner.id(), 40, 10);                       // c: 0.80
        snapshotService.refresh();
        assertThat(rank(a)).isLessThan(rank(b));
        assertThat(rank(b)).isLessThan(rank(c));

        SqlRecorder.start();
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            jdbcTemplate.update("UPDATE challenge_members SET status='LEFT',left_at=UTC_TIMESTAMP(6) WHERE challenge_id=? AND user_id=?",
                    bytes(a), bytes(leaver.id()));
            events.publishEvent(ChallengeStatsRefreshRequested.of(a, "LEAVE"));
        });
        var sql = SqlRecorder.stop();

        assertThat(rank(b)).isLessThan(rank(a));             // a: 45/50 = 0.90
        assertThat(rank(a)).isLessThan(rank(c));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT rank_no FROM challenge_cross_ranking_snapshot WHERE mode='GROUP' AND rank_no IS NOT NULL ORDER BY rank_no");
        for (int i = 0; i < rows.size(); i++) assertThat(((Number) rows.get(i).get("rank_no")).intValue()).isEqualTo(i + 1);
        long rankWrites = sql.stream().filter(e -> e.sql().toLowerCase().contains("rank_no")
                && e.sql().toLowerCase().startsWith("update")).count();
        assertThat(rankWrites).as(sql.toString()).isLessThanOrEqualTo(4);
    }

    /**
     * 회원 탈퇴의 방 정리(외부 나가기, 사유 VOLUNTARY)도 이탈이다. 사유 목록에 없으면 탈퇴한 회원의 참여·성공 수가 다음 일일
     * 갱신까지 방 집계에 남아 남은 사람이 보는 순위가 틀어진다(심사 계정 리셋도 같은 사유로 내보낸다).
     */
    @Test
    @DisplayName("회원 탈퇴로 방을 나가도 그 방 집계에서 곧바로 빠진다")
    void withdrawalLeavesSnapshot() throws Exception {
        Member owner = member(uniq("cross-wd-owner"));
        Member leaver = member(uniq("cross-wd-leaver"));
        UUID a = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(a, owner.id(), "OWNER");
        insertActiveMembership(a, leaver.id(), "MEMBER");
        setDays(a, owner.id(), 40, 10);
        setDays(a, leaver.id(), 50, 0);
        snapshotService.refresh();
        assertThat(jdbcTemplate.queryForObject("SELECT member_count FROM challenge_cross_ranking_snapshot WHERE challenge_id=?",
                Integer.class, bytes(a))).isEqualTo(2);

        memberService.leaveAllForWithdrawal(leaver.id());

        Map<String, Object> snap = jdbcTemplate.queryForMap("SELECT member_count, success_count, total_count "
                + "FROM challenge_cross_ranking_snapshot WHERE challenge_id=?", bytes(a));
        assertThat(((Number) snap.get("member_count")).intValue()).isEqualTo(1);
        assertThat(((Number) snap.get("success_count")).intValue()).isEqualTo(40);
        assertThat(((Number) snap.get("total_count")).intValue()).isEqualTo(50);
    }

    private int rank(UUID challengeId) {
        return jdbcTemplate.queryForObject("SELECT rank_no FROM challenge_cross_ranking_snapshot WHERE challenge_id=?",
                Integer.class, bytes(challengeId));
    }

    private JsonNode ranking(Member viewer, UUID challengeId) throws Exception {
        MvcResult result = getAuth("/api/v1/rankings/challenges?mode=GROUP&size=50&challengeId=" + challengeId,
                viewer.token());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return OM.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void setDays(UUID challengeId, UUID userId, int success, int fail) {
        jdbcTemplate.update("UPDATE challenge_members SET success_days=?,fail_days=? " +
                        "WHERE challenge_id=? AND user_id=?", success, fail, bytes(challengeId), bytes(userId));
    }
}
