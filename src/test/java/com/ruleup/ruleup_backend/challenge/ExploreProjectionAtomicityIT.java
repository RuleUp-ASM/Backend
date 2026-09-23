package com.ruleup.ruleup_backend.challenge;

import com.redis.testcontainers.RedisContainer;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.explore.ExploreSort;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreCircuitBreaker;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexJobs;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexer;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreKeys;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreRedisStore;
import com.ruleup.ruleup_backend.challenge.explore.store.SortKeyCodec;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 파생 인덱스 갱신의 <b>원자성과 순서</b> (탐색 백엔드 3-2 · DB 설계 2-1·3-1).
 *
 * <p>여기 모인 것들은 전부 「동시에 일어나면」에 관한 계약이다. 한 번씩 순서대로 부르면 다
 * 통과하므로, 그 조건을 테스트가 직접 만들지 않으면 깨져도 아무도 모른다 — 실제로 Lua 의
 * 인자 개수 계산 실수와 이전 멤버를 밖에서 읽던 문제가 그렇게 숨어 있었다.
 */
@SpringBootTest(properties = {
        "app.explore.redis.enabled=true",
        "app.explore.redis.open-duration-ms=200"
})
@Import({TestcontainersConfiguration.class, ExploreProjectionAtomicityIT.RedisTestConfig.class})
class ExploreProjectionAtomicityIT extends ChallengeApiSupport {

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

    private UUID publicRoom() throws Exception {
        UUID owner = member(uniq("atom")).id();
        UUID id = insertChallenge(owner, "EXERCISE", "ACTIVE", "GROUP");
        jdbc().update("UPDATE challenges SET visibility = 'PUBLIC', verification_type = 'MANUAL' "
                + "WHERE id = ?", (Object) bytes(id));
        jdbc().update("INSERT INTO challenge_stats (challenge_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE challenge_id = challenge_id", (Object) bytes(id));
        insertActiveMembership(id, owner, "OWNER");
        return id;
    }

    /** 그 방이 정렬 ZSET 에 몇 번 들어 있는지 — 멤버에 값이 박혀 있어 id 로 되짚는다. */
    private long occurrencesIn(ExploreSort sort, UUID challengeId) {
        return store.zsetMembersOf(ExploreKeys.sorted(sort)).stream()
                .filter(m -> SortKeyCodec.idOf(m, sort).equals(challengeId))
                .count();
    }

    @Test
    @DisplayName("[P1] 값이 바뀔 때마다 투영해도 같은 방이 정렬 ZSET 에 두 번 서지 않는다")
    void repeatedProjectionsNeverLeaveADuplicate() throws Exception {
        UUID room = publicRoom();

        // 참여자 수를 바꿔 가며 계속 투영한다 — 매번 정렬 멤버가 달라지므로, 이전 멤버를
        // 제대로 지우지 못하면 그만큼 중복이 쌓인다.
        for (int i = 1; i <= 8; i++) {
            insertActiveMembership(room, member(uniq("atom-j" + i)).id(), "MEMBER");
            indexer.index(room);
        }

        assertThat(occurrencesIn(ExploreSort.PARTICIPANTS, room))
                .as("이전 멤버를 Lua 밖에서 읽으면 동시 갱신이 서로의 멤버를 남겨 같은 방이 여러 번 선다")
                .isEqualTo(1);
        assertThat(occurrencesIn(ExploreSort.POPULAR, room)).isEqualTo(1);
    }

    @Test
    @DisplayName("[P1] 같은 방을 동시에 투영해도 중복이 남지 않는다")
    void concurrentProjectionsConverge() throws Exception {
        UUID room = publicRoom();
        for (int i = 0; i < 5; i++) insertActiveMembership(room, member(uniq("atom-c" + i)).id(), "MEMBER");

        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(6);
        try {
            for (int i = 0; i < 6; i++) {
                pool.submit(() -> {
                    try { start.await(); indexer.index(room); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { done.countDown(); }
                });
            }
            start.countDown();
            done.await();
        } finally {
            pool.shutdownNow();
        }

        assertThat(occurrencesIn(ExploreSort.PARTICIPANTS, room))
                .as("여섯이 같은 이전 값을 읽고 순서대로 적용되면, 나중 것이 중간 갱신의 멤버를 못 지운다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("[P1] 오래된 리비전의 투영은 새 값을 덮지 않는다")
    void anOlderRevisionDoesNotOverwriteANewerOne() throws Exception {
        UUID room = publicRoom();
        indexer.index(room);
        long freshVersion = Long.parseLong(
                store.getStats(room).get(ExploreRedisStore.VERSION_FIELD).toString());

        // 더 낮은 리비전으로 들어온 투영 — 원천을 먼저 읽었다가 늦게 도착한 회차다.
        boolean applied = store.applyProjection(room, freshVersion - 1, System.currentTimeMillis(),
                Map.of("participantCount", "999"),
                List.<String[]>of(new String[]{ExploreKeys.sorted(ExploreSort.PARTICIPANTS),
                        "mem:PARTICIPANTS", ""}),
                List.of(), List.of(), Map.of(), List.of());

        assertThat(applied)
                .as("순서를 정하는 기준은 읽은 시각이 아니라 읽힌 데이터여야 한다")
                .isFalse();
        assertThat(store.getStats(room).get("participantCount"))
                .as("옛 값이 새 값을 덮으면 참여자 수가 오르내리는 것처럼 보인다")
                .isNotEqualTo("999");
    }

    @Test
    @DisplayName("[P1] 지워도 버전은 남는다 — 그 사이 돌던 오래된 투영이 방을 되살리지 못한다")
    void removalLeavesATombstoneSoStaleWritesCannotResurrect() throws Exception {
        UUID room = publicRoom();
        indexer.index(room);
        long before = Long.parseLong(
                store.getStats(room).get(ExploreRedisStore.VERSION_FIELD).toString());

        // 비공개로 바뀌어 후보에서 빠진다.
        jdbc().update("UPDATE challenges SET visibility = 'PRIVATE' WHERE id = ?", (Object) bytes(room));
        indexer.index(room);

        assertThat(store.getStats(room).get(ExploreRedisStore.VERSION_FIELD))
                .as("HASH 를 통째로 지우면 버전이 사라져, 옛 투영이 「기록이 없다」로 읽고 다시 넣는다")
                .isNotNull();
        assertThat(redis.opsForSet().isMember(ExploreKeys.VISIBLE, ExploreKeys.hex(room))).isFalse();

        // 그때 돌고 있던 오래된 공개 투영이 뒤늦게 도착한다.
        boolean resurrected = store.applyProjection(room, before, System.currentTimeMillis(),
                Map.of("participantCount", "1"), List.<String[]>of(),
                List.of(ExploreKeys.VISIBLE), List.of(), Map.of(), List.of());

        assertThat(resurrected)
                .as("비공개로 바꿨는데 목록에 되살아나면 존재 은닉이 뚫린다")
                .isFalse();
        assertThat(redis.opsForSet().isMember(ExploreKeys.VISIBLE, ExploreKeys.hex(room))).isFalse();
    }

    @Test
    @DisplayName("[P1] 정렬 ZSET 하나만 비어도 투영이 온전하지 않다고 본다")
    void aMissingSortedSetIsDetected() throws Exception {
        settle(publicRoom());
        indexer.reindexAll();
        assertThat(indexer.projectionMatchesSource())
                .as("이 시험의 전제 — 막 만든 인덱스는 온전해야 한다").isTrue();

        // 승격 직후 복제가 덜 따라온 모양: 후보는 온전한데 정렬 하나만 비었다.
        redis.delete(ExploreKeys.sorted(ExploreSort.DEADLINE));

        assertThat(indexer.projectionMatchesSource())
                .as("후보 수만 세면 이 상태가 정상으로 읽혀, 그 정렬은 빈 목록을 200 으로 낸다")
                .isFalse();
    }

    @Test
    @DisplayName("[P1] 후보 수가 같아도 내용이 다르면 온전하지 않다고 본다")
    void sameCountButDifferentMembersIsDetected() throws Exception {
        UUID room = publicRoom();
        settle(room);
        indexer.reindexAll();

        // 정상 방 하나가 빠지고 유령 하나가 남은 상태 — 수는 그대로다.
        redis.opsForSet().remove(ExploreKeys.VISIBLE, ExploreKeys.hex(room));
        redis.opsForSet().add(ExploreKeys.VISIBLE, ExploreKeys.hex(UUID.randomUUID()));

        assertThat(indexer.projectionMatchesSource())
                .as("수를 세는 검증은 이 조합을 통과시킨다 — 집합의 내용을 봐야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("[P1] 같은 리비전이어도 먼저 읽은 계산이 나중 계산을 덮지 않는다")
    void withinTheSameRevisionTheLaterReadWins() throws Exception {
        UUID room = publicRoom();
        indexer.index(room);
        Map<Object, Object> hash = store.getStats(room);
        long version = Long.parseLong(hash.get(ExploreRedisStore.VERSION_FIELD).toString());
        long calc = Long.parseLong(hash.get(ExploreRedisStore.CALC_FIELD).toString());

        // 원천은 그대로인데 결과가 다른 경우가 있다 — 24시간 창은 시간만으로 내려가고,
        // challenge_stats 보정은 완주율을 바꾸면서 리비전을 올리지 않는다. 그 둘의 순서는
        // 「누가 더 늦게 읽었는가」로만 가를 수 있다.
        boolean stale = store.applyProjection(room, version, calc - 1,
                Map.of("participantCount", "999"), List.<String[]>of(),
                List.of(), List.of(), Map.of(), List.of());

        assertThat(stale)
                .as("같은 리비전을 무조건 통과시키면 먼저 읽은 낡은 계산이 마지막에 도착해 덮는다")
                .isFalse();
        assertThat(store.getStats(room).get("participantCount")).isNotEqualTo("999");

        // 더 늦게 읽은 계산은 통과해야 한다 — 그러라고 같은 리비전을 막지 않는 것이다.
        assertThat(store.applyProjection(room, version, calc + 1,
                Map.of("participantCount", "7"), List.<String[]>of(),
                List.of(), List.of(), Map.of(), List.of())).isTrue();
        assertThat(store.getStats(room).get("participantCount")).isEqualTo("7");
    }

    @Test
    @DisplayName("[P1] 보정 중 공개된 방을 유령으로 지우고 영영 가려 두지 않는다")
    void aRoomPublishedDuringPruningIsNotTombstonedForever() throws Exception {
        UUID latecomer = publicRoom();
        indexer.index(latecomer);
        assertThat(redis.opsForSet().isMember(ExploreKeys.VISIBLE, ExploreKeys.hex(latecomer))).isTrue();

        // 보정이 이 방을 <b>보기 전에</b> 찍은 스냅샷 — 그때는 아직 비공개였다. 그 뒤 공개로
        // 바뀌고 이벤트가 먼저 투영했으므로, 보정의 눈에는 「원천에 없는 유령」으로 보인다.
        indexer.pruneGhosts(java.util.Set.of());

        assertThat(redis.opsForSet().isMember(ExploreKeys.VISIBLE, ExploreKeys.hex(latecomer)))
                .as("지우기 직전에 원천을 다시 보지 않으면 멀쩡히 공개된 방이 목록에서 빠진다")
                .isTrue();

        // 여기가 진짜 피해다. 「지금 시각」으로 tombstone 을 찍으면 그 방의 원천 리비전보다 큰
        // 버전이 새겨져, 이후 재투영이 전부 거부된다 — 다음 원천 변경까지 영영 가려진다.
        indexer.index(latecomer);
        assertThat(redis.opsForSet().isMember(ExploreKeys.VISIBLE, ExploreKeys.hex(latecomer)))
                .as("tombstone 버전이 원천 리비전보다 크면 재투영이 영영 거부된다")
                .isTrue();
    }

    @Test
    @DisplayName("[P1] 인기 ZSET 만 통째로 비어도 투영이 온전하지 않다고 본다")
    void aMissingTrendingZsetIsDetected() throws Exception {
        settle(publicRoom());
        indexer.reindexAll();
        assertThat(indexer.projectionMatchesSource()).isTrue();

        redis.delete(ExploreKeys.TRENDING_ALL);

        assertThat(indexer.projectionMatchesSource())
                .as("후보·정렬만 보면 이 상태가 정상으로 읽혀, 인기 API 가 빈 200 을 낸다")
                .isFalse();
    }

    @Test
    @DisplayName("[P1] 카테고리 필터 집합이 원천과 다르면 온전하지 않다고 본다")
    void aDriftedCategorySetIsDetected() throws Exception {
        settle(publicRoom());
        indexer.reindexAll();

        String ghost = ExploreKeys.hex(UUID.randomUUID());
        redis.opsForSet().add(ExploreKeys.category("STUDY"), ghost);
        try {
            assertThat(indexer.projectionMatchesSource())
                    .as("필터 집합에 남은 유령은 카테고리 탭에서만 드러난다 — 후보 집합은 멀쩡하다")
                    .isFalse();
        } finally {
            // 스위트가 Redis 를 공유한다 — 남겨 두면 뒤따르는 시험이 전부 이 유령에 걸린다.
            redis.opsForSet().remove(ExploreKeys.category("STUDY"), ghost);
        }
    }

    @Test
    @DisplayName("[P2] 리비전을 마이크로초 그대로 저장한다 — 수로 넘기면 지수 표기로 뭉개진다")
    void revisionsAreStoredWithoutPrecisionLoss() throws Exception {
        UUID room = publicRoom();
        indexer.index(room);

        String version = store.getStats(room).get(ExploreRedisStore.VERSION_FIELD).toString();
        String calc = store.getStats(room).get(ExploreRedisStore.CALC_FIELD).toString();

        assertThat(version)
                .as("Lua 의 수→문자열 변환은 유효숫자 14자리다 — 16자리 리비전이 1.75e+15 로 저장되면 "
                        + "다음 회차의 비교 기준이 통째로 틀어진다")
                .matches("\\d{16,}");
        assertThat(calc).matches("\\d{16,}");
        // 마이크로초라야 같은 밀리초 안의 서로 다른 변경이 한 리비전으로 겹치지 않는다.
        assertThat(Long.parseLong(version) % 1000L != 0 || Long.parseLong(calc) % 1000L != 0)
                .as("전부 1000 의 배수라면 밀리초를 마이크로초로 부풀린 것일 뿐이다")
                .isTrue();
    }

    @Test
    @DisplayName("[P1] 인기 점수만 유실돼도 값 대조가 잡아낸다")
    void aLostTrendingScoreIsDetectedByDeepVerification() throws Exception {
        UUID room = publicRoom();
        settle(room);
        indexer.reindexAll();
        assertThat(indexer.projectionMatchesSource(true)).isTrue();

        // 후보·정렬·소속은 그대로 두고 점수만 망가뜨린다 — 구조만 보는 대조는 통과한다.
        redis.opsForZSet().add(ExploreKeys.TRENDING_ALL, ExploreKeys.hex(room), 999_999d);

        assertThat(indexer.projectionMatchesSource(false))
                .as("소속만 보면 점수 왜곡은 보이지 않는다").isTrue();
        assertThat(indexer.projectionMatchesSource(true))
                .as("카드에 찍히는 가입 수와 순위를 정하는 점수가 어긋난 상태다")
                .isFalse();
    }

    @Test
    @DisplayName("[P1] 표시값이 원천과 달라지면 값 대조가 잡아낸다")
    void aCorruptedDisplayValueIsDetected() throws Exception {
        UUID room = publicRoom();
        settle(room);
        indexer.reindexAll();

        redis.opsForHash().put(ExploreKeys.stats(room), "participantCount", "999");

        assertThat(indexer.projectionMatchesSource(true))
                .as("버전이 원천과 같은데 값이 다르면 그 투영은 거짓말을 하고 있다")
                .isFalse();
    }

    @Test
    @DisplayName("[P1] 같은 방이 정렬 ZSET 에 두 번 서면 대조가 잡아낸다")
    void aDuplicateSortedMemberIsDetected() throws Exception {
        UUID room = publicRoom();
        settle(room);
        indexer.reindexAll();

        // 값만 다른 멤버를 하나 더 넣는다 — id 집합은 그대로라 소속 비교로는 보이지 않는다.
        String existing = store.zsetMembersOf(ExploreKeys.sorted(ExploreSort.PARTICIPANTS)).stream()
                .filter(m -> SortKeyCodec.idOf(m, ExploreSort.PARTICIPANTS).equals(room))
                .findFirst().orElseThrow();
        String twin = (existing.charAt(0) == 'a' ? 'b' : 'a') + existing.substring(1);
        redis.opsForZSet().add(ExploreKeys.sorted(ExploreSort.PARTICIPANTS), twin, 0d);

        assertThat(indexer.projectionMatchesSource(false))
                .as("같은 방이 두 위치에 서면 목록에 두 번 뜬다 — id 집합만 보면 통과한다")
                .isFalse();

        // <b>검출로 끝나면 안 된다.</b> 이 쌍둥이에도 같은 방 id 가 박혀 있어 유령 제거는 「살아
        // 있는 방」으로 보고 지나가고, Lua 는 HASH 가 기억하는 멤버만 지운다 — 전수 재구성이
        // 따로 걷어내지 않으면 영영 남는다. 그 상태로 준비 완료를 올리면 목록은 계속 두 번 뜬다.
        indexer.reindexAll();

        assertThat(occurrencesIn(ExploreSort.PARTICIPANTS, room))
                .as("03:30 대조는 정렬 멤버까지 보정해야 한다(백엔드 9)")
                .isEqualTo(1);
        assertThat(indexer.projectionMatchesSource(false)).isTrue();
    }

    @Test
    @DisplayName("[P1] 통계만 바뀐 방도 5분 보정이 다시 투영한다")
    void aRoomWhoseOnlyChangeIsStatsIsStillReprojected() throws Exception {
        UUID room = publicRoom();
        // 가입 사건을 24시간 창 <b>밖으로</b> 밀어 둔다. 창 안에 가입이 있으면 이 방은 「점수가
        // 내려갈 수 있는 방」으로 매 회차 재투영되어, 「원천이 움직인 방」을 고르는 조건이
        // 맞든 틀리든 시험이 통과한다 — 그 경로를 끊어야 이 시험이 무언가를 증명한다.
        jdbc().update("UPDATE challenge_join_events SET joined_at = NOW(6) - INTERVAL 48 HOUR "
                + "WHERE challenge_id = ?", (Object) bytes(room));
        indexer.index(room);

        String versionBefore = store.getStats(room).get(ExploreRedisStore.VERSION_FIELD).toString();
        assertThat(store.getStats(room).get("completionRate")).isNull();
        assertThat(store.membersWithRecentJoins())
                .as("이 시험의 전제 — 이 방은 인기 하락 경로로는 뽑히지 않는다")
                .doesNotContain(ExploreKeys.hex(room));

        // 챌린지·멤버십·가입 사건은 그대로 두고 통계만 움직인다 — 판정 확정 뒤 재계산이 그 모습이다.
        jdbc().update("UPDATE challenge_stats SET qualified_member_count = 5, "
                + "qualified_success_member_count = 4, completion_rate = 0.8000, updated_at = NOW(6) "
                + "WHERE challenge_id = ?", (Object) bytes(room));

        // 간격으로 넘긴다 — 기준 시각은 원천이 자기 시계로 만든다.
        indexer.reprojectChanged(java.time.Duration.ofMinutes(5));

        assertThat(store.getStats(room).get("completionRate"))
                .as("원천 리비전에는 통계가 들어 있는데 재투영 대상을 고를 때 빠져 있으면, 이 방은 "
                        + "버전이 뒤처진 채 굳고 결국 대조가 「유실」로 읽어 전수 재구성과 503 을 부른다")
                .isEqualTo("0.8");
        assertThat(store.getStats(room).get(ExploreRedisStore.VERSION_FIELD).toString())
                .isNotEqualTo(versionBefore);
    }

    @Test
    @DisplayName("[P1] 보정이 그새 정본이 된 정렬 멤버를 지우지 않는다")
    void repairNeverRemovesAMemberThatBecameCanonicalMeanwhile() throws Exception {
        UUID room = publicRoom();
        indexer.index(room);

        String canonical = store.getStats(room).get("m:" + ExploreSort.PARTICIPANTS.name()).toString();
        assertThat(store.zsetMembersOf(ExploreKeys.sorted(ExploreSort.PARTICIPANTS))).contains(canonical);

        // 보정이 「이건 유물이다」라고 찍어 둔 뒤, 지우기 전에 갱신이 값을 <b>원래대로 되돌린</b>
        // 상황이다 — 참여자가 들어왔다 나가면 정렬 멤버 문자열이 예전 것과 똑같아진다.
        // 그 목록을 그대로 믿고 지우면 HASH 는 가리키는데 ZSET 에는 없는 방이 된다.
        boolean removed = store.removeSortedMemberIfStale(
                ExploreKeys.stats(room), ExploreKeys.sorted(ExploreSort.PARTICIPANTS),
                "m:" + ExploreSort.PARTICIPANTS.name(), canonical);

        assertThat(removed).as("지우는 순간의 HASH 가 판단해야 한다").isFalse();
        assertThat(store.zsetMembersOf(ExploreKeys.sorted(ExploreSort.PARTICIPANTS)))
                .as("보정이 최신을 덮으면 그 방은 다음 원천 변경까지 그 정렬에서 사라진다")
                .contains(canonical);

        // 반대로 정본이 아닌 멤버는 지워져야 한다 — 그러라고 있는 복구다.
        String twin = (canonical.charAt(0) == 'a' ? 'b' : 'a') + canonical.substring(1);
        redis.opsForZSet().add(ExploreKeys.sorted(ExploreSort.PARTICIPANTS), twin, 0d);
        assertThat(store.removeSortedMemberIfStale(
                ExploreKeys.stats(room), ExploreKeys.sorted(ExploreSort.PARTICIPANTS),
                "m:" + ExploreSort.PARTICIPANTS.name(), twin)).isTrue();
        assertThat(store.zsetMembersOf(ExploreKeys.sorted(ExploreSort.PARTICIPANTS)))
                .doesNotContain(twin);
    }

    /**
     * 그 방의 원천을 전부 과거로 민다 — 대조의 유예 대상에서 빼기 위해.
     *
     * <p><b>투영하기 전에 불러야 한다.</b> 이미 투영한 뒤에 부르면 원천 리비전이 거꾸로 가고,
     * 그러면 다음 투영이 「더 오래된 원천」으로 거부된다 — 그건 버전 검사가 옳게 동작한 것이다.
     */
    private void settle(UUID room) {
        jdbc().update("UPDATE challenges SET updated_at = NOW(6) - INTERVAL 1 HOUR WHERE id = ?",
                (Object) bytes(room));
        jdbc().update("UPDATE challenge_members SET updated_at = NOW(6) - INTERVAL 1 HOUR "
                + "WHERE challenge_id = ?", (Object) bytes(room));
        jdbc().update("UPDATE challenge_join_events SET joined_at = NOW(6) - INTERVAL 1 HOUR "
                + "WHERE challenge_id = ?", (Object) bytes(room));
        jdbc().update("UPDATE challenge_stats SET updated_at = NOW(6) - INTERVAL 1 HOUR "
                + "WHERE challenge_id = ?", (Object) bytes(room));
    }

    @Test
    @DisplayName("[P1] 아직 투영되지 않은 정상 커밋이 준비 상태를 내리지 않는다")
    void aFreshCommitAwaitingProjectionIsNotTreatedAsCorruption() throws Exception {
        UUID settled = publicRoom();
        settle(settled);
        indexer.reindexAll();
        assertThat(indexer.projectionMatchesSource(true))
                .as("이 시험의 전제 — 가라앉은 인덱스는 온전하다").isTrue();

        // 방금 공개된 방. 커밋은 끝났지만 AFTER_COMMIT 투영은 아직 도착하지 않았다 —
        // 평시에 늘 있는 상태다. 여기서 「손상」이라고 판정하면 방을 하나 만들 때마다
        // 준비 상태가 내려가고 탐색이 503 을 낸다.
        UUID justCommitted = publicRoom();

        assertThat(indexer.projectionMatchesSource(false))
                .as("정상 지연을 손상으로 읽으면 대조가 고치는 것보다 많이 망가뜨린다")
                .isTrue();
        assertThat(indexer.projectionMatchesSource(true))
                .as("값 대조도 같다 — 막 만들어진 방은 HASH 자체가 아직 없다")
                .isTrue();
        assertThat(redis.opsForSet().isMember(ExploreKeys.VISIBLE, ExploreKeys.hex(justCommitted)))
                .as("이 시험의 전제 — 그 방은 정말 아직 투영 전이다")
                .isFalse();
    }

    @Test
    @DisplayName("[P1] 가라앉은 방의 실제 구조 유실은 여전히 잡아낸다")
    void realLossOnASettledRoomIsStillDetected() throws Exception {
        UUID room = publicRoom();
        settle(room);
        indexer.reindexAll();

        // 유예 구간 밖의 방이 후보에서 사라졌다 — 이건 지연이 아니라 유실이다.
        redis.opsForSet().remove(ExploreKeys.VISIBLE, ExploreKeys.hex(room));

        assertThat(indexer.projectionMatchesSource(false))
                .as("유예가 실제 손상까지 덮으면 대조를 둘 이유가 없다")
                .isFalse();

        indexer.reindexAll();
        assertThat(indexer.projectionMatchesSource(false)).isTrue();
    }

    @Test
    @DisplayName("[P1] 재구성 중 더 새 투영이 끼어들어도 정렬 멤버가 하나만 남는다")
    void aNewerProjectionDuringRebuildLeavesExactlyOneMember() throws Exception {
        UUID room = publicRoom();
        indexer.index(room);

        String snapshotMember = store.getStats(room).get("m:" + ExploreSort.PARTICIPANTS.name()).toString();
        long version = Long.parseLong(
                store.getStats(room).get(ExploreRedisStore.VERSION_FIELD).toString());

        // 「부분 유실 뒤 더 새 투영이 들어온」 상태를 만든다.
        //  · ZSET 에는 유물 A(= snapshotMember)가 남아 있고
        //  · HASH 는 더 새 멤버 B 를 가리키며
        //  · HASH 의 버전이 원천보다 앞서 있어, 스냅샷의 투영은 버전 검사에 막힌다
        String newer = (snapshotMember.charAt(0) == 'a' ? 'b' : 'a') + snapshotMember.substring(1);
        redis.opsForZSet().add(ExploreKeys.sorted(ExploreSort.PARTICIPANTS), newer, 0d);
        redis.opsForHash().put(ExploreKeys.stats(room), "m:" + ExploreSort.PARTICIPANTS.name(), newer);
        redis.opsForHash().put(ExploreKeys.stats(room),
                ExploreRedisStore.VERSION_FIELD, String.valueOf(version + 1000L));

        assertThat(occurrencesIn(ExploreSort.PARTICIPANTS, room))
                .as("이 시험의 전제 — 같은 방이 두 멤버로 서 있다").isEqualTo(2);

        try {
            indexer.reindexAll();

            assertThat(occurrencesIn(ExploreSort.PARTICIPANTS, room))
                    .as("스냅샷에 들어 있다는 이유로 유물을 건너뛰면 둘 다 남는다 — 목록에 두 번 뜬다")
                    .isEqualTo(1);
            assertThat(store.zsetMembersOf(ExploreKeys.sorted(ExploreSort.PARTICIPANTS)))
                    .as("남아야 하는 쪽은 HASH 가 가리키는 더 새 멤버다")
                    .contains(newer)
                    .doesNotContain(snapshotMember);
        } finally {
            // 버전을 미래로 밀어 뒀으니 되돌린다 — 그래야 평범한 제거 경로가 HASH 가 기억하는
            // 멤버를 정렬 여섯 곳에서 모두 걷어낸다. HASH 를 지워 버리면 무엇을 지울지 알 수 없어
            // 나머지 다섯 정렬에 유물이 남는다.
            redis.opsForHash().put(ExploreKeys.stats(room), ExploreRedisStore.VERSION_FIELD, "1");
            jdbc().update("UPDATE challenges SET visibility = 'PRIVATE' WHERE id = ?", (Object) bytes(room));
            indexer.index(room);
            redis.opsForZSet().remove(ExploreKeys.sorted(ExploreSort.PARTICIPANTS), newer, snapshotMember);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("[P1] 첫 대조와 재확인 도중의 정상 커밋은 준비 상태를 내리지 않는다")
    void commitsDuringVerificationDoNotRebuild(boolean needsRepair) throws Exception {
        UUID stable = publicRoom();
        settle(stable);
        // 실제 유실을 함께 만드는 경우에는 15분 증분 보정 대상이 되게 한다.
        jdbc().update("UPDATE challenges SET updated_at = NOW(6) - INTERVAL 5 MINUTE WHERE id = ?",
                (Object) bytes(stable));
        UUID first = publicRoom();
        UUID second = publicRoom();
        for (UUID id : List.of(first, second)) {
            jdbc().update("UPDATE challenges SET visibility = 'PRIVATE' WHERE id = ?", (Object) bytes(id));
            settle(id);
        }
        indexer.reindexAll();
        store.markDeepVerified(java.time.Instant.now());
        assertThat(indexer.projectionMatchesSource(false)).isTrue();
        if (needsRepair) redis.opsForSet().remove(ExploreKeys.VISIBLE, ExploreKeys.hex(stable));

        ExploreCircuitBreaker circuit = wac.getBean(ExploreCircuitBreaker.class);
        ExploreRedisStore observedStore = spy(store);
        ExploreIndexer observedIndexer = spy(new ExploreIndexer(jdbcTemplate, observedStore, circuit));
        var insideVerification = new java.util.concurrent.atomic.AtomicBoolean();
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        var committed = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            rounds.incrementAndGet();
            insideVerification.set(true);
            try { return call.callRealMethod(); }
            finally { insideVerification.set(false); }
        }).when(observedIndexer).projectionMatchesSource(anyBoolean());
        doAnswer(call -> {
            // 원천 스냅샷을 읽은 뒤, 첫 Redis 읽기 직전에 정상 COMMIT + 투영을 끼워 넣는다.
            // 첫 대조가 증분 보정으로 이어지면 재확인에서도 다른 방에 같은 경합을 만든다.
            if (insideVerification.get() && committed.get() < 2) {
                UUID id = List.of(first, second).get(committed.getAndIncrement());
                jdbc().update("UPDATE challenges SET visibility = 'PUBLIC', updated_at = NOW(6) WHERE id = ?",
                        (Object) bytes(id));
                indexer.index(id);
            }
            return call.callRealMethod();
        }).when(observedStore).membersOf(ExploreKeys.VISIBLE);

        ExploreIndexJobs jobs = new ExploreIndexJobs(observedIndexer, observedStore, circuit,
                wac.getBean(ChallengeStatsReconciliationService.class));
        jobs.warmUpOnStartup();

        assertThat(rounds.get()).as("실제 유실만 증분 보정과 재확인을 유발한다")
                .isEqualTo(needsRepair ? 2 : 1);
        assertThat(committed.get()).isEqualTo(rounds.get());
        assertThat(indexer.projectionMatchesSource(false)).isTrue();
        assertThat(store.membersOf(ExploreKeys.VISIBLE)).contains(ExploreKeys.hex(stable));
        verify(observedStore, never()).clearWarmed();
        verify(observedIndexer, never()).reindexAll();
    }

    @Test
    @DisplayName("[P1] 값 대조 도중의 카테고리 변경도 정상 커밋으로 처리한다")
    void categoryCommitDuringDeepVerificationIsNotCorruption() throws Exception {
        UUID room = publicRoom();
        settle(room);
        indexer.reindexAll();
        ExploreRedisStore observedStore = spy(store);
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            Object oldHash = call.callRealMethod();
            if (committed.compareAndSet(false, true)) {
                jdbc().update("UPDATE challenges SET category = 'STUDY', updated_at = NOW(6) WHERE id = ?",
                        (Object) bytes(room));
                indexer.index(room);
            }
            return oldHash;
        }).when(observedStore).getStats(room);

        ExploreIndexer observedIndexer = new ExploreIndexer(jdbcTemplate, observedStore,
                wac.getBean(ExploreCircuitBreaker.class));
        assertThat(observedIndexer.projectionMatchesSource(true)).isTrue();
        assertThat(committed.get()).isTrue();
        assertThat(store.membersOf(ExploreKeys.category("STUDY"))).contains(ExploreKeys.hex(room));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("[P1] 대조 도중 비공개 전환 또는 하드 삭제된 방은 정상적으로 빠진다")
    void removalDuringVerificationIsNotCorruption(boolean hardDelete) throws Exception {
        UUID room = publicRoom();
        settle(room);
        indexer.reindexAll();
        ExploreRedisStore observedStore = spy(store);
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            if (committed.compareAndSet(false, true)) {
                if (hardDelete) {
                    var tx = new org.springframework.transaction.support.TransactionTemplate(
                            wac.getBean(org.springframework.transaction.PlatformTransactionManager.class));
                    tx.executeWithoutResult(status -> wac.getBean(
                            com.ruleup.ruleup_backend.challenge.service.ChallengeHardDeleter.class).hardDelete(room));
                } else {
                    jdbc().update("UPDATE challenges SET visibility = 'PRIVATE', updated_at = NOW(6) WHERE id = ?",
                            (Object) bytes(room));
                    indexer.index(room);
                }
            }
            return call.callRealMethod();
        }).when(observedStore).membersOf(ExploreKeys.VISIBLE);

        ExploreIndexer observedIndexer = new ExploreIndexer(jdbcTemplate, observedStore,
                wac.getBean(ExploreCircuitBreaker.class));
        assertThat(observedIndexer.projectionMatchesSource(true)).isTrue();
        assertThat(committed.get()).isTrue();
        assertThat(store.membersOf(ExploreKeys.VISIBLE)).doesNotContain(ExploreKeys.hex(room));
    }
}
