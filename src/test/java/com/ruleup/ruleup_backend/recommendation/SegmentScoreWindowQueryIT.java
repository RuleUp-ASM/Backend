package com.ruleup.ruleup_backend.recommendation;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.DbTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 세그먼트 재집계 대상 쿼리 — 예전에 앱이 {@code findAll()} 뒤에 걸러내던 조건을 DB 가 똑같이 거르는지.
 *  - 윈도우 경계: created_at 이 since 와 같으면 포함, 그보다 이르면 제외({@code isBefore(since)} 제외와 같다)
 *  - 삭제된 방·직접 입력(템플릿 없음)·봇방장 방 제외
 *  - (생성자, 템플릿)별 개수
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SegmentScoreWindowQueryIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ChallengeRepository challengeRepository;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    /** 생성자·템플릿·생성 시각을 정한 챌린지 1건. */
    private UUID challenge(UUID creatorId, Long templateId, Instant createdAt) {
        UUID id = insertChallenge(creatorId, "EXERCISE", "ACTIVE", "GROUP");
        jdbcTemplate.update("UPDATE challenges SET template_id = ?, created_at = ? WHERE id = ?",
                templateId, DbTime.utc(createdAt), bytes(id));
        return id;
    }

    @Test
    @DisplayName("윈도우 안·삭제 X·템플릿 기반·생성자 있는 챌린지만 (생성자, 템플릿)별로 센다")
    void filtersAndGroupsLikeTheOldInMemoryLoop() throws Exception {
        UUID a = member("seg_a").id();
        UUID b = member("seg_b").id();
        long t1 = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        long t2 = t1 + 1;
        // DATETIME(6) 에 그대로 들어가도록 마이크로초로 자른다
        Instant since = Instant.now().minus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MICROS);

        challenge(a, t1, since);                                   // 경계와 같음 → 포함
        challenge(a, t1, since.plus(Duration.ofDays(10)));         // 윈도우 안 → 포함
        challenge(a, t2, Instant.now());                           // 다른 템플릿 → 따로 센다
        challenge(b, t1, since.plus(Duration.ofDays(1)));          // 다른 생성자 → 따로 센다
        challenge(a, t1, since.minus(1, ChronoUnit.MICROS));       // 경계 직전 → 제외
        challenge(b, t2, since.minus(Duration.ofDays(5)));         // 윈도우 밖 → 제외
        challenge(a, null, Instant.now());                         // 직접 입력 → 제외
        UUID deleted = challenge(b, t1, Instant.now());            // 삭제됨 → 제외
        jdbcTemplate.update("UPDATE challenges SET deleted_at = ? WHERE id = ?",
                DbTime.utc(Instant.now()), bytes(deleted));
        UUID botOwned = challenge(a, t1, Instant.now());           // 봇방장 → 제외
        jdbcTemplate.update("UPDATE challenges SET owner_type = 'BOT', owner_id = NULL WHERE id = ?",
                bytes(botOwned));

        List<Object[]> rows = challengeRepository.countTemplateChoicesByCreatorSince(since).stream()
                .filter(r -> Set.of(t1, t2).contains((Long) r[1]))   // 공유 DB 의 다른 테스트 행은 뺀다
                .toList();

        assertThat(rows)
                .extracting(r -> tuple(r[0], r[1], r[2]))
                .containsExactlyInAnyOrder(
                        tuple(a, t1, 2L),
                        tuple(a, t2, 1L),
                        tuple(b, t1, 1L));
    }
}
