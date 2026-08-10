package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 목록 조회 계약 테스트 — 탐색 정책 §4 · API 명세 explore · 백엔드 테크스펙 §11-3.
 *
 * <p>처리 순서는 <b>① 노출 제외 → ② 필터 AND → ③ 정렬</b>이다. 이 순서가 흐트러지면
 * 비공개 방이 필터를 타고 새거나, 표본 미달 방이 완주율 정렬에 끼어 왜곡된 순위를 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeExploreListIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        // 다른 테스트가 만든 공개 방이 목록에 섞이지 않게 후보에서 뺀다.
        jdbcTemplate.update("UPDATE challenges SET visibility = 'PRIVATE' WHERE visibility IS NOT NULL");
    }

    // ===== 픽스처 =====

    /** 공개 그룹 방 1개(+ stats 행). 기본은 ACTIVE. */
    private UUID room(String category, String status) throws Exception {
        UUID owner = member(uniq("ex")).id();
        UUID id = insertChallenge(owner, category, status, "GROUP");
        jdbcTemplate.update("UPDATE challenges SET visibility = 'PUBLIC', verification_type = 'MANUAL' WHERE id = ?",
                (Object) bytes(id));
        jdbcTemplate.update("INSERT INTO challenge_stats (challenge_id) VALUES (?) " +
                "ON DUPLICATE KEY UPDATE challenge_id = challenge_id", (Object) bytes(id));
        return id;
    }

    private void set(UUID challengeId, String column, Object value) {
        jdbcTemplate.update("UPDATE challenges SET " + column + " = ? WHERE id = ?", value, bytes(challengeId));
    }

    private void stats(UUID challengeId, String column, Object value) {
        jdbcTemplate.update("UPDATE challenge_stats SET " + column + " = ? WHERE challenge_id = ?",
                value, bytes(challengeId));
    }

    private MvcResult explore(String token, String query) throws Exception {
        return getAuth("/api/v1/challenges/explore" + (query.isEmpty() ? "" : "?" + query), token);
    }

    private List<String> ids(MvcResult res) throws Exception {
        return read(res, "$.data.items[*].challengeId");
    }

    // =====================================================================
    @Nested
    @DisplayName("① 노출 제외")
    class Exclusion {

        @Test
        @DisplayName("비공개·솔로·종료 방은 목록에 나오지 않는다")
        void hidesPrivateSoloCompleted() throws Exception {
            String token = memberToken(uniq("ex-hide"));
            UUID visible = room("EXERCISE", "ACTIVE");
            UUID priv = room("EXERCISE", "ACTIVE");
            set(priv, "visibility", "PRIVATE");
            UUID solo = room("EXERCISE", "ACTIVE");
            set(solo, "mode", "SOLO");
            room("EXERCISE", "COMPLETED");

            assertThat(ids(explore(token, ""))).containsExactly(visible.toString());
        }

        @Test
        @DisplayName("내가 신고한 방은 내 화면에서만 빠진다")
        void hidesMyReportedRooms() throws Exception {
            var reporter = member(uniq("ex-reporter"));
            String other = memberToken(uniq("ex-other"));
            UUID reported = room("EXERCISE", "ACTIVE");
            UUID kept = room("EXERCISE", "ACTIVE");

            jdbcTemplate.update("INSERT INTO reports " +
                            "(id, reporter_id, target_type, target_challenge_id, context_type, reason) " +
                            "VALUES (?, ?, 'CHALLENGE', ?, 'CHALLENGE_DETAIL', 'SPAM')",
                    bytes(UUID.randomUUID()), bytes(reporter.id()), bytes(reported));

            assertThat(ids(explore(reporter.token(), ""))).containsExactly(kept.toString());
            assertThat(ids(explore(other, ""))).contains(reported.toString(), kept.toString());
        }

        @Test
        @DisplayName("정원이 찬 방은 빼지 않고 isFull 로 구분한다 — 탈퇴로 자리가 날 수 있다")
        void fullRoomStaysVisible() throws Exception {
            String token = memberToken(uniq("ex-full"));
            UUID full = room("EXERCISE", "ACTIVE");
            jdbcTemplate.update("UPDATE challenges SET capacity = 5, participant_count = 5 WHERE id = ?",
                    (Object) bytes(full));

            MvcResult res = explore(token, "");
            assertThat(ids(res)).containsExactly(full.toString());
            assertThat((Boolean) read(res, "$.data.items[0].isFull")).isTrue();
        }

        @Test
        @DisplayName("시작 전 방은 노출하되 진행 지표는 전부 null 이다")
        void upcomingHasNoMetrics() throws Exception {
            String token = memberToken(uniq("ex-upcoming"));
            UUID upcoming = room("EXERCISE", "UPCOMING");

            MvcResult res = explore(token, "");
            assertThat(ids(res)).containsExactly(upcoming.toString());
            assertThat((Boolean) read(res, "$.data.items[0].startsSoon")).isTrue();
            assertThat((Object) read(res, "$.data.items[0].completionRate")).isNull();
            assertThat((Object) read(res, "$.data.items[0].retentionRate")).isNull();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("② 필터 — 선택한 것을 모두 만족(AND), 카테고리끼리는 OR")
    class Filters {

        @Test
        @DisplayName("카테고리는 복수 선택 OR 로 걸린다")
        void categoriesAreOr() throws Exception {
            String token = memberToken(uniq("ex-cat"));
            UUID exercise = room("EXERCISE", "ACTIVE");
            UUID study = room("STUDY", "ACTIVE");
            room("READING", "ACTIVE");

            assertThat(ids(explore(token, "categories=EXERCISE,STUDY")))
                    .containsExactlyInAnyOrder(exercise.toString(), study.toString());
        }

        @Test
        @DisplayName("인증 방식과 티어 컷은 카테고리와 AND 로 결합된다")
        void filtersAreAnded() throws Exception {
            String token = memberToken(uniq("ex-and"));
            UUID wanted = room("EXERCISE", "ACTIVE");
            set(wanted, "verification_type", "AUTO");
            UUID manual = room("EXERCISE", "ACTIVE");           // verifyType 불일치
            UUID otherCategory = room("STUDY", "ACTIVE");
            set(otherCategory, "verification_type", "AUTO");     // 카테고리 불일치

            assertThat(ids(explore(token, "categories=EXERCISE&verifyType=AUTO")))
                    .containsExactly(wanted.toString());
        }

        @Test
        @DisplayName("티어 컷은 기본 off 이고, 켜면 내 표시 티어로 들어갈 수 있는 방만 남는다")
        void eligibleOnlyIsOptIn() throws Exception {
            String token = memberToken(uniq("ex-tier"));
            UUID open = room("EXERCISE", "ACTIVE");
            UUID locked = room("EXERCISE", "ACTIVE");
            set(locked, "min_tier", "DIAMOND");

            // 기본 off — 못 들어가는 방도 보인다
            MvcResult all = explore(token, "");
            assertThat(ids(all)).contains(open.toString(), locked.toString());
            assertThat((List<Boolean>) read(all, "$.data.items[?(@.minTier == 'DIAMOND')].eligible"))
                    .containsExactly(false);

            assertThat(ids(explore(token, "eligibleOnly=true"))).containsExactly(open.toString());
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("③ 정렬 — 단일 적용, 기본 인기순")
    class Sorting {

        @Test
        @DisplayName("기본 정렬은 인기순이다")
        void defaultIsPopular() throws Exception {
            String token = memberToken(uniq("ex-pop"));
            UUID low = room("EXERCISE", "ACTIVE");
            stats(low, "recent_joins_24h", 1);
            UUID high = room("EXERCISE", "ACTIVE");
            stats(high, "recent_joins_24h", 9);

            assertThat(ids(explore(token, ""))).containsExactly(high.toString(), low.toString());
        }

        @Test
        @DisplayName("참여자 수·최근 생성·마감 임박 정렬")
        void otherSorts() throws Exception {
            String token = memberToken(uniq("ex-sorts"));
            UUID few = room("EXERCISE", "ACTIVE");
            set(few, "participant_count", 2);
            jdbcTemplate.update("UPDATE challenges SET end_date = DATE_ADD(CURDATE(), INTERVAL 30 DAY), " +
                    "created_at = DATE_SUB(NOW(6), INTERVAL 10 DAY) WHERE id = ?", (Object) bytes(few));
            UUID many = room("EXERCISE", "ACTIVE");
            set(many, "participant_count", 20);
            jdbcTemplate.update("UPDATE challenges SET end_date = DATE_ADD(CURDATE(), INTERVAL 2 DAY), " +
                    "created_at = NOW(6) WHERE id = ?", (Object) bytes(many));

            assertThat(ids(explore(token, "sort=PARTICIPANTS"))).containsExactly(many.toString(), few.toString());
            assertThat(ids(explore(token, "sort=RECENT"))).containsExactly(many.toString(), few.toString());
            assertThat(ids(explore(token, "sort=DEADLINE"))).containsExactly(many.toString(), few.toString());
        }

        @Test
        @DisplayName("완주율·유지율 정렬은 표본 미달 방을 목록에서 아예 뺀다")
        void sampleShortRoomsDropOut() throws Exception {
            String token = memberToken(uniq("ex-sample"));
            UUID withMetrics = room("EXERCISE", "ACTIVE");
            stats(withMetrics, "completion_rate", 0.7);
            stats(withMetrics, "retention_rate", 0.9);
            UUID noMetrics = room("EXERCISE", "ACTIVE");   // 지표 null

            assertThat(ids(explore(token, "sort=COMPLETION_RATE"))).containsExactly(withMetrics.toString());
            assertThat(ids(explore(token, "sort=SUCCESS_FAIL_RATIO"))).containsExactly(withMetrics.toString());
            // 다른 정렬에서는 그대로 보인다
            assertThat(ids(explore(token, "sort=RECENT"))).contains(noMetrics.toString());
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("커서 페이징과 오류")
    class PagingAndErrors {

        @Test
        @DisplayName("size 기본 10 · 최대 20이고 totalCount 는 주지 않는다")
        void pagingContract() throws Exception {
            String token = memberToken(uniq("ex-page"));
            // 공유 DB라 다른 테스트의 방이 섞이지 않게 이 테스트 전용 카테고리로 격리한다.
            for (int i = 0; i < 12; i++) {
                UUID id = room("HOBBY", "ACTIVE");
                stats(id, "recent_joins_24h", 100 - i);   // 결정적 순서
            }

            MvcResult first = explore(token, "categories=HOBBY");
            assertThat((Integer) read(first, "$.data.items.length()")).isEqualTo(10);
            assertThat((Boolean) read(first, "$.data.hasNext")).isTrue();
            // 전체 개수는 계약에서 뺐다 — 필드 자체가 응답에 없어야 한다
            assertThat(first.getResponse().getContentAsString()).doesNotContain("totalCount");

            String cursor = read(first, "$.data.nextCursor");
            assertThat(cursor).isNotBlank();
            MvcResult second = explore(token, "categories=HOBBY&cursor=" + cursor);
            assertThat((Integer) read(second, "$.data.items.length()")).isEqualTo(2);
            assertThat((Boolean) read(second, "$.data.hasNext")).isFalse();
            assertThat((Object) read(second, "$.data.nextCursor")).isNull();
            // 페이지가 겹치지 않는다
            assertThat(ids(second)).doesNotContainAnyElementsOf(ids(first));

            assertThat((Integer) read(explore(token, "categories=HOBBY&size=50"), "$.data.items.length()"))
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("정렬이 바뀐 커서는 거부한다 — 목록이 섞여 중복·누락이 생긴다")
        void cursorIsBoundToSort() throws Exception {
            String token = memberToken(uniq("ex-cursor-sort"));
            for (int i = 0; i < 11; i++) room("MIND", "ACTIVE");

            String cursor = read(explore(token, "categories=MIND&sort=RECENT"), "$.data.nextCursor");
            expectError(explore(token, "categories=MIND&sort=PARTICIPANTS&cursor=" + cursor), 400, "CURSOR_INVALID");
        }

        @Test
        @DisplayName("잘못된 정렬·필터·커서는 각각 다른 코드로 거절한다")
        void badInputs() throws Exception {
            String token = memberToken(uniq("ex-bad"));
            expectError(explore(token, "sort=NOPE"), 400, "INVALID_SORT_TYPE");
            expectError(explore(token, "categories=NOT_A_CATEGORY"), 400, "INVALID_FILTER_VALUE");
            expectError(explore(token, "verifyType=SOMETHING"), 400, "INVALID_FILTER_VALUE");
            expectError(explore(token, "cursor=!!!broken!!!"), 400, "CURSOR_INVALID");
        }
    }
}
