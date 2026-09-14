package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexer;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreKeys;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreRedisStore;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 탐색 테크스펙 개정 정합 — 인기 표시·필터 집합·상세·복제 (공통 5-2·5-3·5-4, 백엔드 6-2·7-5).
 *
 * <p>여기 모인 것들은 서로 다른 지점이지만 공통점이 하나 있다 — <b>파생값이 원천보다 오래
 * 살아남는 자리</b>다. 인증 방식을 바꿔도 옛 필터 집합에 남고, 재입장해도 참여 이력이 안 남고,
 * 계산 시각 대신 요청 시각이 내려간다. 전부 「한 번 들어간 값이 나오지 않는」 모양이다.
 */
@SpringBootTest(properties = {
        "app.explore.redis.enabled=true",
        "app.explore.redis.open-duration-ms=200"
})
@Import({TestcontainersConfiguration.class, ExploreSpecAlignmentIT.RedisTestConfig.class})
class ExploreSpecAlignmentIT extends ChallengeApiSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisTestConfig {
        @Bean
        @ServiceConnection
        RedisContainer redisContainer() {
            return new RedisContainer(DockerImageName.parse("redis:7-alpine"));
        }
    }

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ExploreIndexer indexer;
    @Autowired ExploreRedisStore store;
    @Autowired StringRedisTemplate redis;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    /** 공개 그룹 방 하나. 인덱스에도 반영해 탐색 후보로 만든다. */
    private UUID publicRoom(String category) throws Exception {
        UUID owner = member(uniq("esa-owner")).id();
        UUID id = insertChallenge(owner, category, "ACTIVE", "GROUP");
        jdbc().update("UPDATE challenges SET visibility = 'PUBLIC' WHERE id = ?", (Object) bytes(id));
        jdbc().update("INSERT INTO challenge_stats (challenge_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE challenge_id = challenge_id", (Object) bytes(id));
        insertActiveMembership(id, owner, "OWNER");
        return id;
    }

    private void setVerifyType(UUID challengeId, String type) {
        // 인덱서가 읽는 것은 JSON 이 아니라 verification_type 컬럼이다.
        jdbc().update("UPDATE challenges SET verification_type = ? WHERE id = ?", type, bytes(challengeId));
    }

    // =====================================================================
    @Nested
    @DisplayName("인기 표시값")
    class Trending {

        @Test
        @DisplayName("[P1] calculatedAt 은 계산 시각이다 — 요청할 때마다 지금으로 찍으면 지연을 표시할 수 없다")
        void calculatedAtIsWhenTheIndexWasBuiltNotWhenAsked() throws Exception {
            Member me = member(uniq("esa-calc"));
            publicRoom("EXERCISE");
            indexer.reindexAll();
            Instant indexedAt = Instant.now();

            Thread.sleep(1200);
            MvcResult res = getAuth("/api/v1/challenges/trending", me.token());

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            Instant calculatedAt = Instant.parse((String) read(res, "$.data.calculatedAt"));
            assertThat(calculatedAt)
                    .as("클라가 「몇 분 전 기준」을 표시하려면 계산 시각이어야 한다 — "
                            + "요청 시각을 내리면 항상 0 초 전이 된다")
                    .isBefore(indexedAt.plusMillis(1100));
        }

        @Test
        @DisplayName("[P1] 내가 신고해 차단한 방은 인기에서도 빠진다")
        void blockedChallengesAreExcludedFromTrending() throws Exception {
            Member me = member(uniq("esa-block"));
            UUID blocked = publicRoom("EXERCISE");
            jdbc().update("INSERT IGNORE INTO user_blocks (blocker_id, target_type, target_id) "
                    + "VALUES (?, 'CHALLENGE', ?)", bytes(me.id()), bytes(blocked));
            indexer.reindexAll();

            MvcResult res = getAuth("/api/v1/challenges/trending", me.token());

            assertThat((java.util.List<String>) read(res, "$.data.items[*].challengeId"))
                    .as("목록에서만 빼고 인기에 남기면 신고한 방을 홈에서 다시 만난다")
                    .doesNotContain(blocked.toString());
        }

        @Test
        @DisplayName("[P1] 재입장도 인기 상승으로 잡힌다 — 참여는 사건이지 상태가 아니다")
        void rejoiningCountsAsAFreshJoin() throws Exception {
            Member me = member(uniq("esa-rejoin"));
            UUID room = publicRoom("EXERCISE");

            // 오래전에 들어왔다 나간 사람. 그대로면 24시간 창 밖이라 인기에 잡히지 않는다.
            insertActiveMembership(room, me.id(), "MEMBER");
            jdbc().update("UPDATE challenge_members SET status = 'LEFT', "
                    + "joined_at = DATE_SUB(NOW(6), INTERVAL 10 DAY), "
                    + "left_at = DATE_SUB(NOW(6), INTERVAL 9 DAY), rejoin_available_at = NULL "
                    + "WHERE challenge_id = ? AND user_id = ?", bytes(room), bytes(me.id()));
            // 그때의 가입 사건도 10일 전으로 — 그대로면 24시간 창 안이라 시험이 성립하지 않는다.
            jdbc().update("UPDATE challenge_join_events SET joined_at = DATE_SUB(NOW(6), INTERVAL 10 DAY) "
                    + "WHERE challenge_id = ? AND user_id = ?", bytes(room), bytes(me.id()));

            assertThat(postJsonAuth("/api/v1/challenges/" + room + "/members", me.token(), Map.of())
                    .getResponse().getStatus()).isEqualTo(200);

            Integer recent = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM challenge_join_events WHERE challenge_id = ? AND user_id = ? "
                            + "AND joined_at >= DATE_SUB(NOW(6), INTERVAL 24 HOUR)",
                    Integer.class, bytes(room), bytes(me.id()));
            assertThat(recent)
                    .as("멤버십 한 줄로는 여러 번의 가입을 담을 수 없다 — 사건을 사건으로 남겨야 "
                            + "「최근 24시간 신규 참여」가 사실과 맞는다")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("필터 집합")
    class FilterSets {

        @Test
        @DisplayName("[P1] 인증 방식을 바꾸면 이전 집합에서 빠진다 — 남으면 옛 필터 결과에 계속 뜬다")
        void changingVerifyTypeRemovesTheRoomFromTheOldSet() throws Exception {
            UUID room = publicRoom("EXERCISE");
            setVerifyType(room, "AUTO");
            indexer.index(room);
            assertThat(redis.opsForSet().isMember(ExploreKeys.verifyType("AUTO"), ExploreKeys.hex(room)))
                    .as("이 시험의 전제 — 먼저 AUTO 집합에 들어가 있어야 한다").isTrue();

            setVerifyType(room, "MANUAL");
            indexer.index(room);

            assertThat(redis.opsForSet().isMember(ExploreKeys.verifyType("AUTO"), ExploreKeys.hex(room)))
                    .as("추가만 하고 지우지 않으면 다음 전체 재구성까지 AUTO 결과에 잘못 노출된다")
                    .isFalse();
            assertThat(redis.opsForSet().isMember(ExploreKeys.verifyType("MANUAL"), ExploreKeys.hex(room)))
                    .isTrue();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("공개 상세와 복제")
    class DetailAndClone {

        @Test
        @DisplayName("[P2] 상세에 weeklyCount 가 내려간다 — 명세의 필수 필드다")
        void detailIncludesWeeklyCount() throws Exception {
            Member me = member(uniq("esa-weekly"));
            UUID room = publicRoom("EXERCISE");

            MvcResult res = getAuth("/api/v1/challenges/" + room, me.token());

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Integer) read(res, "$.data.weeklyCount"))
                    .as("주 몇 회인지 없이는 카드가 목표를 설명할 수 없다")
                    .isNotNull();
        }

        @Test
        @DisplayName("[P2] 상세의 참여자 수와 정원 마감은 같은 원천을 본다 — 서로 모순되면 안 된다")
        void participantCountAndIsFullAgree() throws Exception {
            Member me = member(uniq("esa-agree"));
            UUID room = publicRoom("EXERCISE");
            jdbc().update("UPDATE challenges SET capacity = 5, participant_count = 0 WHERE id = ?",
                    (Object) bytes(room));

            MvcResult res = getAuth("/api/v1/challenges/" + room, me.token());

            assertThat((Integer) read(res, "$.data.participantCount"))
                    .as("isFull 은 실시간 COUNT 로 재면서 참여자 수만 비동기 표시값을 내리면 "
                            + "「0명인데 마감」 같은 카드가 나온다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("[P2] 비공개 방 복제는 볼 수 없는 사람에게도 NOT_CLONEABLE 403 이다")
        void cloningAPrivateRoomIsForbiddenNotHidden() throws Exception {
            Member me = member(uniq("esa-clone"));
            UUID room = publicRoom("EXERCISE");
            jdbc().update("UPDATE challenges SET visibility = 'PRIVATE' WHERE id = ?", (Object) bytes(room));

            MvcResult res = postJsonAuth("/api/v1/challenges/" + room + "/clone", me.token(), Map.of());

            assertThat(res.getResponse().getStatus())
                    .as("복제 API 명세는 비공개·솔로를 403 NOT_CLONEABLE 로 규정한다")
                    .isEqualTo(403);
            assertThat((String) read(res, "$.error.code")).isEqualTo("NOT_CLONEABLE");
        }
    }
}
