package com.ruleup.ruleup_backend.challenge;

import com.redis.testcontainers.RedisContainer;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexJobs;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexer;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreKeys;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreRedisStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 워밍업·대조 배치가 스펙의 복구 조건을 채우는지 (공통 5-3·5-5-3, 백엔드 8-3·9).
 *
 * <p>세 가지가 걸려 있다.
 * <ul>
 *   <li><b>재구성 중에도 목록이 살아 있어야 한다.</b> 파생 인덱스로만 응답하게 된 뒤로는,
 *       「비우고 다시 채우는」 재구성이 곧 매일 밤의 503 구간이 된다.</li>
 *   <li><b>대조는 원천에서 다시 계산해야 한다.</b> 이미 계산된 값을 읽어 옮기기만 하면,
 *       그 값이 틀어졌을 때 대조가 틀린 값을 충실히 복사한다 — 복구가 아니다.</li>
 *   <li><b>여러 인스턴스가 동시에 돌면 안 된다.</b> 전수 재구성이 겹치면 서로의 중간 상태를
 *       지우며 경합한다.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.explore.redis.enabled=true",
        "app.explore.redis.open-duration-ms=200"
})
@Import({TestcontainersConfiguration.class, ExploreReconciliationIT.RedisTestConfig.class})
class ExploreReconciliationIT extends ChallengeApiSupport {

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
    @Autowired ExploreIndexJobs jobs;
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
        UUID owner = member(uniq("rec-owner")).id();
        UUID id = insertChallenge(owner, "EXERCISE", "ACTIVE", "GROUP");
        jdbc().update("UPDATE challenges SET visibility = 'PUBLIC' WHERE id = ?", (Object) bytes(id));
        jdbc().update("INSERT INTO challenge_stats (challenge_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE challenge_id = challenge_id", (Object) bytes(id));
        insertActiveMembership(id, owner, "OWNER");
        return id;
    }

    @Test
    @DisplayName("[P1] 재구성 중에도 후보가 비지 않는다 — 비우고 채우면 그 사이가 매일 밤의 503 이다")
    void rebuildNeverLeavesTheIndexEmpty() throws Exception {
        UUID room = publicRoom();
        indexer.reindexAll();
        assertThat(redis.opsForSet().isMember(ExploreKeys.VISIBLE, ExploreKeys.hex(room))).isTrue();

        // 재구성이 도는 동안 다른 스레드가 계속 들여다본다. 한 번이라도 비어 있으면 그 순간
        // 목록·인기는 503 이다 — 예전에는 flushDerived() 가 그 창을 매번 열었다.
        AtomicInteger sawEmpty = new AtomicInteger();
        ExecutorService watcher = Executors.newSingleThreadExecutor();
        CountDownLatch done = new CountDownLatch(1);
        watcher.submit(() -> {
            while (done.getCount() > 0) {
                Long size = redis.opsForSet().size(ExploreKeys.VISIBLE);
                if (size != null && size == 0L) sawEmpty.incrementAndGet();
            }
        });
        try {
            for (int i = 0; i < 5; i++) indexer.reindexAll();
        } finally {
            done.countDown();
            watcher.shutdownNow();
        }

        assertThat(sawEmpty.get())
                .as("재구성은 유령을 걷어내는 일이지, 목록을 잠시 없애는 일이 아니다")
                .isZero();
    }

    @Test
    @DisplayName("[P1] 재구성은 원천에서 사라진 방을 걷어낸다 — 유령 제거가 이 배치의 존재 이유다")
    void rebuildStillRemovesGhosts() throws Exception {
        UUID room = publicRoom();
        indexer.reindexAll();
        assertThat(redis.opsForSet().isMember(ExploreKeys.VISIBLE, ExploreKeys.hex(room))).isTrue();

        // 원천에서 빠졌다(비공개 전환). 증분 갱신은 이런 행을 발견할 방법이 없다.
        jdbc().update("UPDATE challenges SET visibility = 'PRIVATE' WHERE id = ?", (Object) bytes(room));
        indexer.reindexAll();

        assertThat(redis.opsForSet().isMember(ExploreKeys.VISIBLE, ExploreKeys.hex(room)))
                .as("비우지 않고 채우기만 하면 유령이 남는다 — 두 요구를 함께 만족해야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("[P1] 03:30 대조는 통계를 원천에서 다시 계산한 뒤 반영한다")
    void nightlyReconciliationRecomputesFromTheSource() throws Exception {
        UUID room = publicRoom();
        indexer.reindexAll();
        // 스냅샷이 틀어진 상태를 만든다. 이미 계산된 값을 옮기기만 하면 이 값이 그대로 실린다.
        jdbc().update("UPDATE challenge_stats SET completion_rate = 0.99, qualified_member_count = 999 "
                + "WHERE challenge_id = ?", (Object) bytes(room));

        jobs.reconcile();

        Double rate = jdbc().queryForObject(
                "SELECT completion_rate FROM challenge_stats WHERE challenge_id = ?",
                Double.class, bytes(room));
        assertThat(rate)
                .as("대조가 틀린 값을 충실히 복사하면 그건 복구가 아니다 — 표본 미달이라 null 이어야 한다")
                .isNull();
    }

    @Test
    @DisplayName("[P1] 같은 시각에 두 인스턴스가 돌아도 전수 재구성은 하나만 수행한다")
    void onlyOneInstanceRebuildsAtATime() throws Exception {
        publicRoom();
        int before = runCount();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    jobs.reconcile();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }
        start.countDown();
        finished.await();
        pool.shutdownNow();

        assertThat(runCount() - before)
                .as("전수 재구성이 겹치면 서로의 중간 상태를 지우며 경합한다")
                .isEqualTo(1);
    }

    /** 전수 재구성이 몇 번 수행됐는지 — 잠금에 막힌 호출은 세지 않는다. */
    private int runCount() {
        String raw = redis.opsForValue().get(ExploreKeys.RECONCILE_RUNS);
        return raw == null ? 0 : Integer.parseInt(raw);
    }
}
