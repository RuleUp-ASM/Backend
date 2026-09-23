package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.explore.PopularityRefreshJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 홈 실시간 인기 · 카테고리 그리드 계약 테스트 — 탐색 정책 §3 · 공통 테크스펙 §3 · 백엔드 §9~§11.
 *
 * <p>가장 중요한 축은 <b>존재 은닉</b>이다. 비공개·솔로 방의 제목·이미지·참여자 수가 인기 카드나
 * 카테고리 카운트로 새면 안 된다. 그 다음이 인기 산식(24시간 신규 참여 수, 동점이면 최근 참여 우선)과
 * "인기에는 필터를 적용하지 않는다"는 규칙이다 — 못 들어가는 방도 보이되 잠금 표시만 한다.
 */
@SpringBootTest(properties = {
        "app.explore.redis.enabled=true",
        "app.explore.redis.open-duration-ms=200"
})
@Import({TestcontainersConfiguration.class, ChallengeTrendingCategoryIT.RedisTestConfig.class})
class ChallengeTrendingCategoryIT extends ChallengeApiSupport {

    /**
     * 인기·목록은 파생 인덱스로만 응답한다 — 준비되지 않으면 503 이다(공통 5-4).
     * 예전에는 MySQL 폴백이 있어 Redis 없이도 이 테스트가 돌았지만, 그 폴백이 곧 스펙 위반이었다.
     */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class RedisTestConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.boot.testcontainers.service.connection.ServiceConnection
        com.redis.testcontainers.RedisContainer redisContainer() {
            return new com.redis.testcontainers.RedisContainer(
                    org.testcontainers.utility.DockerImageName.parse("redis:7-alpine"));
        }
    }

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PopularityRefreshJob popularityRefreshJob;
    @Autowired com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexer exploreIndexer;
    @Autowired CacheManager cacheManager;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        // 다른 테스트가 만든 방이 순위에 섞이지 않게 후보를 비우고 시작한다.
        jdbcTemplate.update("UPDATE challenges SET visibility = 'PRIVATE' WHERE visibility IS NOT NULL");
        jdbcTemplate.update("UPDATE challenge_stats SET recent_joins_24h = 0, last_joined_at_24h = NULL");
        evictCaches();
    }

    private void evictCaches() {
        List.of("challengeTrending", "challengeCategories").forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }

    // ===== 픽스처 =====

    /** 공개 그룹 방 1개. {@code joins} 명 만큼 최근 24시간 안에 가입한 이력을 함께 만든다. */
    private UUID publicGroup(String category, String status, int joins, int minutesAgo) throws Exception {
        UUID owner = member(uniq("tr")).id();
        UUID id = insertChallenge(owner, category, status, "GROUP");
        jdbcTemplate.update("UPDATE challenges SET visibility = 'PUBLIC' WHERE id = ?", (Object) bytes(id));
        jdbcTemplate.update("INSERT INTO challenge_stats (challenge_id) VALUES (?) " +
                "ON DUPLICATE KEY UPDATE challenge_id = challenge_id", (Object) bytes(id));
        for (int i = 0; i < joins; i++) {
            UUID joiner = member(uniq("trj")).id();
            insertActiveMembership(id, joiner, "MEMBER");
            jdbcTemplate.update("UPDATE challenge_members SET joined_at = DATE_SUB(NOW(6), INTERVAL ? MINUTE) " +
                    "WHERE challenge_id = ? AND user_id = ?", minutesAgo, bytes(id), bytes(joiner));
            // 인기 집계가 세는 것은 <b>가입 사건</b>이다 — 멤버십만 뒤로 미루면 창이 움직이지 않는다.
            jdbcTemplate.update("UPDATE challenge_join_events SET joined_at = DATE_SUB(NOW(6), INTERVAL ? MINUTE) " +
                    "WHERE challenge_id = ? AND user_id = ?", minutesAgo, bytes(id), bytes(joiner));
        }
        jdbcTemplate.update("UPDATE challenges SET participant_count = ? WHERE id = ?", joins, bytes(id));
        return id;
    }

    /**
     * 인기·목록은 파생 인덱스가 준비돼야 응답한다(준비 전에는 503). 픽스처를 MySQL 에 심었으면
     * 여기서 한 번 반영해 주어야 그 방들이 후보가 된다 — 운영에서는 COMMIT 후 갱신과 5분 스윕이
     * 하는 일이다.
     */
    private void reindex() {
        exploreIndexer.reindexAll();
    }

    private MvcResult trending(String token, String category) throws Exception {
        reindex();
        String url = "/api/v1/challenges/trending" + (category != null ? "?category=" + category : "");
        return getAuth(url, token);
    }

    // =====================================================================
    @Nested
    @DisplayName("실시간 인기")
    class Trending {

        @Test
        @DisplayName("최근 24시간 신규 참여가 많은 방이 위로, 동점이면 더 최근에 몰린 쪽이 위")
        void rankByRecentJoins() throws Exception {
            String token = memberToken(uniq("tr-rank"));
            UUID few = publicGroup("EXERCISE", "ACTIVE", 1, 60);
            UUID many = publicGroup("EXERCISE", "ACTIVE", 3, 60);
            UUID tieOlder = publicGroup("EXERCISE", "ACTIVE", 1, 600);

            popularityRefreshJob.runOnce();
            evictCaches();

            MvcResult res = trending(token, null);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.items[0].challengeId")).isEqualTo(many.toString());
            assertThat((Integer) read(res, "$.data.items[0].recentJoins24h")).isEqualTo(3);
            // 1명끼리 동점 — 60분 전이 600분 전보다 위
            assertThat((String) read(res, "$.data.items[1].challengeId")).isEqualTo(few.toString());
            assertThat((String) read(res, "$.data.items[2].challengeId")).isEqualTo(tieOlder.toString());
            assertThat((Integer) read(res, "$.data.items[0].rank")).isEqualTo(1);
            assertThat((String) read(res, "$.data.calculatedAt")).isNotBlank();
        }

        @Test
        @DisplayName("24시간이 지난 참여는 인기 점수에서 빠진다")
        void windowIs24Hours() throws Exception {
            String token = memberToken(uniq("tr-window"));
            UUID stale = publicGroup("EXERCISE", "ACTIVE", 5, 60 * 25);

            popularityRefreshJob.runOnce();
            evictCaches();

            MvcResult res = trending(token, null);
            assertThat((Integer) read(res, "$.data.items.length()")).isEqualTo(1);
            assertThat((String) read(res, "$.data.items[0].challengeId")).isEqualTo(stale.toString());
            assertThat((Integer) read(res, "$.data.items[0].recentJoins24h")).isZero();
        }

        @Test
        @DisplayName("비공개·솔로·종료 방은 후보에서 빠진다 — 인기 카드로 존재가 새면 안 된다")
        void hidesPrivateSoloCompleted() throws Exception {
            String token = memberToken(uniq("tr-hide"));
            UUID visible = publicGroup("EXERCISE", "ACTIVE", 2, 30);

            UUID priv = publicGroup("EXERCISE", "ACTIVE", 9, 30);
            jdbcTemplate.update("UPDATE challenges SET visibility = 'PRIVATE' WHERE id = ?", (Object) bytes(priv));
            UUID solo = publicGroup("EXERCISE", "ACTIVE", 9, 30);
            jdbcTemplate.update("UPDATE challenges SET mode = 'SOLO' WHERE id = ?", (Object) bytes(solo));
            UUID done = publicGroup("EXERCISE", "COMPLETED", 9, 30);

            popularityRefreshJob.runOnce();
            evictCaches();

            MvcResult res = trending(token, null);
            List<String> ids = read(res, "$.data.items[*].challengeId");
            assertThat(ids).containsExactly(visible.toString());
        }

        @Test
        @DisplayName("캐시 생성 뒤 비공개·솔로·종료로 바뀐 방도 응답 시점에 다시 제외한다")
        void revalidatesCachedCandidates() throws Exception {
            String token = memberToken(uniq("tr-stale"));
            UUID kept = publicGroup("EXERCISE", "ACTIVE", 1, 30);
            UUID privateAfterCache = publicGroup("EXERCISE", "ACTIVE", 4, 30);
            UUID soloAfterCache = publicGroup("EXERCISE", "ACTIVE", 3, 30);
            UUID completedAfterCache = publicGroup("EXERCISE", "ACTIVE", 2, 30);

            popularityRefreshJob.runOnce();
            evictCaches();
            // 첫 요청으로 랭킹 ID 스냅샷을 캐시에 넣는다.
            assertThat((List<String>) read(trending(token, null), "$.data.items[*].challengeId"))
                    .contains(privateAfterCache.toString(), soloAfterCache.toString(), completedAfterCache.toString());

            jdbcTemplate.update("UPDATE challenges SET visibility = 'PRIVATE' WHERE id = ?",
                    (Object) bytes(privateAfterCache));
            jdbcTemplate.update("UPDATE challenges SET mode = 'SOLO' WHERE id = ?", (Object) bytes(soloAfterCache));
            jdbcTemplate.update("UPDATE challenges SET status = 'COMPLETED' WHERE id = ?",
                    (Object) bytes(completedAfterCache));

            List<String> ids = read(trending(token, null), "$.data.items[*].challengeId");
            assertThat(ids).containsExactly(kept.toString());
        }

        @Test
        @DisplayName("시작 전(UPCOMING) 방은 인기 후보에 포함된다")
        void upcomingIncluded() throws Exception {
            String token = memberToken(uniq("tr-upcoming"));
            UUID upcoming = publicGroup("EXERCISE", "UPCOMING", 2, 30);

            popularityRefreshJob.runOnce();
            evictCaches();

            List<String> ids = read(trending(token, null), "$.data.items[*].challengeId");
            assertThat(ids).contains(upcoming.toString());
        }

        @Test
        @DisplayName("인기에는 티어 필터를 적용하지 않는다 — 못 들어가는 방도 보이고 joinable=false로만 표시")
        void tierIsShownNotFiltered() throws Exception {
            String token = memberToken(uniq("tr-tier"));
            UUID locked = publicGroup("EXERCISE", "ACTIVE", 2, 30);
            jdbcTemplate.update("UPDATE challenges SET min_tier = 'DIAMOND' WHERE id = ?", (Object) bytes(locked));

            popularityRefreshJob.runOnce();
            evictCaches();

            MvcResult res = trending(token, null);
            assertThat((String) read(res, "$.data.items[0].challengeId")).isEqualTo(locked.toString());
            assertThat((String) read(res, "$.data.items[0].minTier")).isEqualTo("DIAMOND");
            assertThat((Boolean) read(res, "$.data.items[0].joinable")).isFalse();
        }

        @Test
        @DisplayName("category를 주면 그 카테고리의 인기만 내려준다")
        void perCategory() throws Exception {
            String token = memberToken(uniq("tr-cat"));
            UUID exercise = publicGroup("EXERCISE", "ACTIVE", 3, 30);
            UUID study = publicGroup("STUDY", "ACTIVE", 5, 30);

            popularityRefreshJob.runOnce();
            evictCaches();

            List<String> ids = read(trending(token, "EXERCISE"), "$.data.items[*].challengeId");
            assertThat(ids).containsExactly(exercise.toString());
            assertThat(ids).doesNotContain(study.toString());
        }

        @Test
        @DisplayName("후보가 없으면 빈 목록이다 — 갱신 전에도 500이 아니다")
        void emptyIsFine() throws Exception {
            String token = memberToken(uniq("tr-empty"));
            MvcResult res = trending(token, null);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Integer) read(res, "$.data.items.length()")).isZero();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("카테고리 그리드")
    class CategoryGrid {

        @Test
        @DisplayName("관심 분야 정책 12종을 고정 순서로 내려주고, 없으면 0이다")
        void twelveFixedCategories() throws Exception {
            String token = memberToken(uniq("cat-fixed"));
            MvcResult res = getAuth("/api/v1/challenge-categories", token);

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Integer) read(res, "$.data.items.length()")).isEqualTo(12);
            List<String> codes = read(res, "$.data.items[*].code");
            assertThat(codes).containsExactly(
                    "EXERCISE", "WAKE_SLEEP", "DIET_HEALTH", "STUDY", "READING", "MIND",
                    "FINANCE", "HOBBY", "HOUSEKEEPING", "CAREER_PRODUCTIVITY", "DETOX", "ETC");
            assertThat((String) read(res, "$.data.items[0].name")).isEqualTo("운동");
        }

        @Test
        @DisplayName("[P1] 진행 중인 공개 그룹만 센다 — 시작 전·비공개·솔로·종료는 빠진다")
        void countsJoinablePublicGroupOnly() throws Exception {
            String token = memberToken(uniq("cat-count"));
            publicGroup("READING", "ACTIVE", 0, 30);          // 셈

            UUID priv = publicGroup("READING", "ACTIVE", 0, 30);
            jdbcTemplate.update("UPDATE challenges SET visibility = 'PRIVATE' WHERE id = ?", (Object) bytes(priv));
            UUID solo = publicGroup("READING", "ACTIVE", 0, 30);
            jdbcTemplate.update("UPDATE challenges SET mode = 'SOLO' WHERE id = ?", (Object) bytes(solo));
            publicGroup("READING", "COMPLETED", 0, 30);       // 안 셈 — 끝난 방이다
            // 인기·목록은 모집 중 방을 포함하지만 카테고리 수는 진행 중 방만 센다 —
            // 스펙이 「의도된 비대칭」이라 못 박은 지점이다(공통 3절).
            publicGroup("READING", "UPCOMING", 0, 30);        // 안 셈

            evictCaches();
            MvcResult res = getAuth("/api/v1/challenge-categories", token);
            List<Integer> reading = read(res, "$.data.items[?(@.code == 'READING')].activeGroupCount");
            assertThat(reading).containsExactly(1);
        }

        @Test
        @DisplayName("[P1] 로그인하지 않으면 카테고리 수도 내려주지 않는다")
        void categoriesRequireAuthentication() throws Exception {
            assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/challenge-categories"))
                    .andReturn().getResponse().getStatus())
                    .as("탐색 5개 API 는 모두 로그인 필요다 — 공개하면 방 수가 그대로 새는 지표가 된다")
                    .isEqualTo(401);
        }
    }
}
