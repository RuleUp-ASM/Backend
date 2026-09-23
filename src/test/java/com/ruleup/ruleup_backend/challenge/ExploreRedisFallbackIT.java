package com.ruleup.ruleup_backend.challenge;

import com.redis.testcontainers.RedisContainer;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.explore.ExploreQueryService;
import com.ruleup.ruleup_backend.challenge.explore.ExploreSort;
import com.ruleup.ruleup_backend.challenge.explore.TrendingRankingSource;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreCircuitBreaker;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexer;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreRedisStore;
import com.ruleup.ruleup_backend.challenge.dto.ExploreResponse;
import com.ruleup.ruleup_backend.challenge.explore.ExploreCursor;
import com.ruleup.ruleup_backend.challenge.explore.ExploreDataSource;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 탐색의 두 저장소 경로 (탐색 테크스펙 5-1 · 5-3 · 5-5-3).
 *
 * <p>Redis 를 정렬 원천으로 쓰기로 한 이상 <b>지켜야 할 것이 셋</b>이고, 이 스위트가 그 셋을 잡는다.
 * <ol>
 *   <li><b>두 경로의 순서가 같다.</b> ZSET 사전순과 {@code ORDER BY} 가 어긋나면 폴백이 일어나는
 *       순간 목록이 뒤섞인다. 정렬 6종 전부를 대조한다.</li>
 *   <li><b>Redis 가 죽으면 조용히 SQL 로 간다.</b> 빈 목록을 정상으로 취급하면 인기 섹션이
 *       소리 없이 사라진다 — 가장 나쁜 실패 방식이다.</li>
 *   <li><b>경로가 바뀐 커서는 거부한다.</b> Redis 커서와 MySQL 커서는 같은 필드에 전혀 다른
 *       의미가 들어가므로, 이어 붙이면 방이 중복되거나 통째로 건너뛰어진다.</li>
 * </ol>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ExploreRedisFallbackIT.RedisTestConfig.class})
@TestPropertySource(properties = {
        "app.explore.redis.enabled=true",
        // 회로가 열린 뒤 곧바로 다시 시도할 수 있어야 테스트가 복구 경로까지 본다.
        "app.explore.redis.open-duration-ms=200"
})
class ExploreRedisFallbackIT extends ChallengeApiSupport {

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
    @Autowired ExploreQueryService exploreQueryService;
    @Autowired TrendingRankingSource trendingRankingSource;
    @Autowired ExploreIndexer indexer;
    @Autowired ExploreRedisStore store;
    @Autowired ExploreCircuitBreaker circuit;
    @Autowired com.ruleup.ruleup_backend.challenge.explore.PopularityRefreshJob popularityRefreshJob;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        closeCircuit();
    }

    /** 회로를 강제로 닫아 Redis 경로로 보낸다 — 앞선 테스트가 열어 뒀을 수 있다. */
    private void closeCircuit() {
        ReflectionTestUtils.setField(circuit, "openUntil",
                new java.util.concurrent.atomic.AtomicReference<java.time.Instant>());
        ReflectionTestUtils.setField(circuit, "consecutiveFailures",
                new java.util.concurrent.atomic.AtomicInteger());
        ReflectionTestUtils.setField(circuit, "enabled", true);
    }

    private void useMysqlOnly() {
        ReflectionTestUtils.setField(circuit, "enabled", false);
    }

    /**
     * 정렬이 갈리도록 값이 서로 다른 공개 그룹 방 여러 개.
     *
     * <p><b>인기 값을 손으로 심지 않고 실제 멤버 행에서 만든다.</b> Redis 인덱서는 참여 이력을
     * 즉시 세고 SQL 폴백은 {@code challenge_stats} 스냅샷을 읽으므로, 스냅샷을 임의값으로 심으면
     * 두 경로가 서로 다른 값을 보게 된다 — 그건 구현 버그가 아니라 픽스처 버그다. 원천을 만들고
     * 스냅샷 배치를 한 번 돌려 <b>둘이 같은 값을 보는 상태</b>에서 순서를 대조한다.
     */
    private List<UUID> seedChallenges(UUID ownerId, int count) throws Exception {
        // 참여자 풀 — 방마다 다른 인원이 들어가야 인기·참여자 수 정렬이 갈린다.
        List<UUID> joiners = new ArrayList<>();
        for (int i = 0; i < count; i++) joiners.add(member(uniq("joiner")).id());

        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = insertChallenge(ownerId, (i % 2 == 0) ? "EXERCISE" : "READING", "ACTIVE", "GROUP");
            // visibility 는 nullable 이고 insertChallenge 가 채우지 않는다 — 비워 두면 노출 후보에서 빠진다.
            jdbcTemplate.update(
                    "UPDATE challenges SET visibility = 'PUBLIC', verification_type = 'MANUAL', " +
                            " participant_count = ?, end_date = DATE_ADD(end_date, INTERVAL ? DAY) " +
                            "WHERE id = ?", i + 1, i, bytes(id));

            for (int j = 0; j <= i; j++) {
                insertActiveMembership(id, joiners.get(j), j == 0 ? "OWNER" : "MEMBER");
            }
            // 마지막 참여 시각을 방마다 어긋나게 둬서 인기 동점 처리(2차 키)까지 검증한다.
            jdbcTemplate.update(
                    "UPDATE challenge_members SET joined_at = DATE_SUB(NOW(6), INTERVAL ? MINUTE) " +
                            "WHERE challenge_id = ?", i + 1, bytes(id));

            jdbcTemplate.update(
                    "INSERT INTO challenge_stats (challenge_id, qualified_member_count, total_progress_count, " +
                            " completion_rate, retention_rate) VALUES (?, 6, 40, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE completion_rate = VALUES(completion_rate), " +
                            " retention_rate = VALUES(retention_rate)",
                    bytes(id), 0.10 * (i + 1), 0.05 * (i + 1));
            ids.add(id);
        }
        // 폴백이 읽을 스냅샷을 원천과 맞춘 뒤 파생 인덱스를 만든다 — 이 순서라야 두 경로가 같은 값을 본다.
        popularityRefreshJob.runOnce();
        indexer.reindexAll();
        return ids;
    }

    private List<String> idsOf(ExploreResponse response) {
        return response.items().stream().map(ExploreResponse.Item::challengeId).toList();
    }

    // =================================================================
    @ParameterizedTest
    @EnumSource(ExploreSort.class)
    @DisplayName("정렬 6종 전부 같은 요청에 같은 순서를 낸다 — 서버가 달라도 순위가 갈리지 않는다")
    void everySortIsStableAcrossRequests(ExploreSort sort) throws Exception {
        Member viewer = member(uniq("parity"));
        seedChallenges(viewer.id(), 7);

        closeCircuit();
        List<String> first = idsOf(exploreQueryService.explore(
                viewer.id(), null, null, false, sort.name(), null, 20));
        List<String> again = idsOf(exploreQueryService.explore(
                viewer.id(), null, null, false, sort.name(), null, 20));

        assertThat(first)
                .as("%s 정렬은 파생 인덱스 하나로만 결정된다 — 같은 인덱스면 순서도 같다", sort)
                .isNotEmpty()
                .containsExactlyElementsOf(again);
    }

    @Test
    @DisplayName("커서 페이징이 경계 행을 중복·누락 없이 이어 붙인다")
    void cursorPagingCoversEveryRowOnce() throws Exception {
        Member viewer = member(uniq("paging"));
        seedChallenges(viewer.id(), 12);

        closeCircuit();
        List<String> paged = collectPages(viewer.id(), 3, 3);
        List<String> atOnce = idsOf(exploreQueryService.explore(
                viewer.id(), null, null, false, "POPULAR", null, 9));

        assertThat(paged).hasSize(9).doesNotHaveDuplicates();
        assertThat(paged)
                .as("쪼개 받은 순서가 한 번에 받은 순서와 달라지면 경계에서 방이 새거나 겹친다")
                .containsExactlyElementsOf(atOnce);
    }

    /** 커서를 이어 {@code pages} 장을 모은다. 경로는 호출 전에 이미 정해져 있어야 한다. */
    private List<String> collectPages(UUID userId, int pages, int size) {
        List<String> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < pages; page++) {
            ExploreResponse res = exploreQueryService.explore(
                    userId, null, null, false, "POPULAR", cursor, size);
            all.addAll(idsOf(res));
            cursor = res.nextCursor();
            if (cursor == null) break;
        }
        return all;
    }

    @Test
    @DisplayName("[P1] 인기 후보가 비면 MySQL 로 대신 내리지 않고 503 이다")
    void trendingIsUnavailableRatherThanServedFromMysql() throws Exception {
        Member viewer = member(uniq("outage"));
        seedChallenges(viewer.id(), 4);

        // 파생 인덱스를 통째로 날려 "Redis 는 살아 있는데 내용이 없다"를 만든다.
        store.flushDerived();

        assertThatThrownBy(() -> trendingRankingSource.ranking(null))
                .as("MySQL 로 대신 내리면 순위가 서버마다 달라지고, 장애가 조용히 덮인다 — "
                        + "스펙은 이 구간을 EXPLORE_TEMPORARILY_UNAVAILABLE 로 드러내라고 못 박았다")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(BusinessException.class))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.EXPLORE_TEMPORARILY_UNAVAILABLE);
    }

    @Test
    @DisplayName("[P1] 워밍업 전에는 목록도 503 이다 — 반쯤 찬 인덱스로도, 다른 저장소로도 내리지 않는다")
    void exploreIsUnavailableBeforeWarmUp() throws Exception {
        Member viewer = member(uniq("warmup"));
        seedChallenges(viewer.id(), 5);

        store.flushDerived();       // warmed 플래그까지 사라진다
        assertThat(store.isWarmed()).isFalse();

        assertThatThrownBy(() -> exploreQueryService.explore(
                viewer.id(), null, null, false, "POPULAR", null, 3))
                .as("원천 워밍업이 끝날 때까지는 503 이고, 끝나면 목록이 돌아온다")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(BusinessException.class))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.EXPLORE_TEMPORARILY_UNAVAILABLE);
    }

    @Test
    @DisplayName("[P1] 다음 페이지를 받기 전에 인덱스가 내려가면 503 — 반쪽 목록을 이어 붙이지 않는다")
    void pagingStopsWithUnavailableWhenTheIndexGoesDown() throws Exception {
        Member viewer = member(uniq("cursorsrc"));
        seedChallenges(viewer.id(), 5);

        closeCircuit();
        ExploreResponse first = exploreQueryService.explore(
                viewer.id(), null, null, false, "POPULAR", null, 2);
        assertThat(first.nextCursor()).isNotBlank();

        // 다음 페이지 요청 사이에 파생 인덱스가 죽었다. 예전에는 MySQL 로 내려가며
        // CURSOR_INVALID 를 돌려줬지만, 이제 내려갈 곳이 없다.
        useMysqlOnly();
        assertThatThrownBy(() -> exploreQueryService.explore(
                viewer.id(), null, null, false, "POPULAR", first.nextCursor(), 2))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.EXPLORE_TEMPORARILY_UNAVAILABLE));
    }

    @Test
    @DisplayName("필터는 Redis 집합 교차로 걸러지고, 걸러진 결과도 원천의 조건을 만족한다")
    void filtersNarrowTheCandidatesAndStayTrueToTheSource() throws Exception {
        Member viewer = member(uniq("filter"));
        seedChallenges(viewer.id(), 6);

        closeCircuit();
        List<String> filtered = idsOf(exploreQueryService.explore(
                viewer.id(), "EXERCISE", "MANUAL", false, "PARTICIPANTS", null, 20));

        assertThat(filtered).isNotEmpty();
        // 집합 교차만 믿지 않고 원천으로 되짚는다 — 집합이 낡으면 조건에 안 맞는 방이 섞인다.
        for (String id : filtered) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT category, JSON_UNQUOTE(JSON_EXTRACT(verification_config, '$.verificationType')) AS vt "
                            + "FROM challenges WHERE id = UNHEX(REPLACE(?, '-', ''))", id);
            assertThat(row.get("category")).isEqualTo("EXERCISE");
            assertThat(row.get("vt")).isEqualTo("MANUAL");
        }
    }
}
