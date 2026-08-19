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
 * 내 챌린지 목록(GET /api/v1/challenges) — 명세 계약.
 *
 * <p>탭 하나가 아니라 홈·마이페이지의 세 화면이 전부 이 API 를 쓰므로, 잠글 것은 세 가지다.
 * <ul>
 *   <li>탭이 서로 새지 않는다 — 진행 중 탭에 완료·이탈 건이 섞이면 화면이 거짓말을 한다.</li>
 *   <li>이탈 경위는 LEFT 탭에서만 실린다.</li>
 *   <li><b>완료 기록은 방이 삭제된 뒤에도 남는다</b> — 삭제 배치가 방을 통째로 지우므로,
 *       이력을 읽지 않으면 완료 탭이 시간이 지날수록 비어 버린다.</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MyChallengeListApiIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Nested
    @DisplayName("탭 필터")
    class Tabs {

        @Test
        @DisplayName("기본 탭은 진행 중이고, 완료·이탈 건은 섞이지 않는다")
        void inProgressIsDefaultAndIsolated() throws Exception {
            Member me = member(uniq("list-basic"));
            UUID active = joined(me, "ACTIVE");
            UUID completed = joined(me, "COMPLETED");
            UUID left = joined(me, "ACTIVE");
            leave(left, me);

            assertThat(idsOf(list(me, null))).containsExactly(active.toString());
            assertThat(idsOf(list(me, "?filter=COMPLETED"))).containsExactly(completed.toString());
            assertThat(idsOf(list(me, "?filter=LEFT"))).containsExactly(left.toString());
        }

        @Test
        @DisplayName("시작 전(UPCOMING) 방도 진행 중 탭에 들어온다")
        void upcomingBelongsToInProgress() throws Exception {
            Member me = member(uniq("list-upcoming"));
            UUID upcoming = joined(me, "UPCOMING");

            assertThat(idsOf(list(me, "?filter=IN_PROGRESS"))).containsExactly(upcoming.toString());
        }

        @Test
        @DisplayName("탭 값이 목록에 없으면 조용히 기본값으로 떨어뜨리지 않고 400 이다")
        void unknownFilterIsRejected() throws Exception {
            Member me = member(uniq("list-badfilter"));
            MvcResult res = getAuth("/api/v1/challenges?filter=ARCHIVED", me.token());

            assertThat(res.getResponse().getStatus()).isEqualTo(400);
            assertThat((String) read(res, "$.error.code")).isEqualTo("INVALID_FILTER_VALUE");
        }
    }

    @Nested
    @DisplayName("항목 계약")
    class Items {

        @Test
        @DisplayName("카드에는 탐색 상세와 같은 어휘가 실린다 — mode·capacity·weeklyCount·ownerType")
        void itemCarriesSpecFields() throws Exception {
            Member me = member(uniq("list-fields"));
            UUID challengeId = joined(me, "ACTIVE");

            MvcResult res = list(me, null);
            assertThat((String) read(res, "$.data.challenges[0].challengeId")).isEqualTo(challengeId.toString());
            assertThat((String) read(res, "$.data.challenges[0].mode")).isEqualTo("GROUP");
            assertThat((Integer) read(res, "$.data.challenges[0].capacity")).isEqualTo(50);
            assertThat((Integer) read(res, "$.data.challenges[0].weeklyCount")).isEqualTo(7);
            assertThat((String) read(res, "$.data.challenges[0].ownerType")).isEqualTo("USER");
            assertThat((String) read(res, "$.data.challenges[0].myRole")).isEqualTo("OWNER");
            // 이탈 경위는 LEFT 탭 전용이다 — 진행 중 카드에 남아 있으면 화면이 이탈 배지를 그린다
            assertThat((String) read(res, "$.data.challenges[0].leftType")).isNull();
            assertThat((String) read(res, "$.data.challenges[0].leftAt")).isNull();
        }

        @Test
        @DisplayName("이탈 탭은 어떻게 나갔는지와 나간 시각을 함께 준다")
        void leftTabCarriesLeftType() throws Exception {
            Member me = member(uniq("list-left"));
            UUID challengeId = joined(me, "ACTIVE");
            leave(challengeId, me);

            MvcResult res = list(me, "?filter=LEFT");
            assertThat((String) read(res, "$.data.challenges[0].leftType")).isEqualTo("SELF");
            assertThat((String) read(res, "$.data.challenges[0].leftAt")).isNotNull();
        }

        @Test
        @DisplayName("심사 중이면 제목은 AI 임시 제목, 설명·이미지는 빈 값으로 대체된다")
        void moderationFallbackApplies() throws Exception {
            Member me = member(uniq("list-moderation"));
            UUID challengeId = joined(me, "ACTIVE");
            jdbcTemplate.update("UPDATE challenges SET title=?, ai_title=?, description=?, image_url=?, " +
                            " moderation_title='IN_REVIEW', moderation_description='IN_REVIEW', " +
                            " moderation_image='IN_REVIEW' WHERE id=?",
                    "원본 제목", "AI 임시 제목", "원본 설명", "https://cdn.ruleup.co.kr/c/x.png", bytes(challengeId));

            MvcResult res = list(me, null);
            assertThat((String) read(res, "$.data.challenges[0].title")).isEqualTo("AI 임시 제목");
            assertThat((String) read(res, "$.data.challenges[0].description")).isNull();
            assertThat((String) read(res, "$.data.challenges[0].imageUrl")).isNull();
        }
    }

    @Nested
    @DisplayName("페이지네이션")
    class Paging {

        @Test
        @DisplayName("size 를 넘어가면 nextCursor 로 이어 읽고 마지막 페이지에서 멈춘다")
        void cursorWalksEveryRowExactlyOnce() throws Exception {
            Member me = member(uniq("list-paging"));
            List<UUID> all = List.of(joined(me, "ACTIVE"), joined(me, "ACTIVE"), joined(me, "ACTIVE"));

            MvcResult first = list(me, "?size=2");
            assertThat((Boolean) read(first, "$.data.hasNext")).isTrue();
            String cursor = read(first, "$.data.nextCursor");
            MvcResult second = list(me, "?size=2&cursor=" + cursor);
            assertThat((Boolean) read(second, "$.data.hasNext")).isFalse();
            assertThat((String) read(second, "$.data.nextCursor")).isNull();

            assertThat(idsOf(first).size() + idsOf(second).size()).isEqualTo(3);
            assertThat(idsOf(first)).doesNotContainAnyElementsOf(idsOf(second));
            assertThat(all.stream().map(UUID::toString).toList())
                    .containsExactlyInAnyOrderElementsOf(concat(idsOf(first), idsOf(second)));
        }

        @Test
        @DisplayName("깨진 커서는 빈 목록이 아니라 400 이다 — 처음부터 다시 부르게 해야 한다")
        void brokenCursorIsRejected() throws Exception {
            Member me = member(uniq("list-badcursor"));
            MvcResult res = getAuth("/api/v1/challenges?cursor=not-a-cursor", me.token());

            assertThat(res.getResponse().getStatus()).isEqualTo(400);
            assertThat((String) read(res, "$.error.code")).isEqualTo("CURSOR_INVALID");
        }
    }

    @Test
    @DisplayName("완료된 방이 하드 삭제돼도 완료 탭에는 이력으로 남는다")
    void deletedCompletedChallengeSurvivesInHistory() throws Exception {
        Member me = member(uniq("list-history"));
        UUID challengeId = joined(me, "COMPLETED");
        archiveAndDelete(challengeId, me);

        MvcResult res = list(me, "?filter=COMPLETED");
        assertThat(idsOf(res)).containsExactly(challengeId.toString());
        assertThat((String) read(res, "$.data.challenges[0].title")).isEqualTo("삭제된 방 제목");
        assertThat((String) read(res, "$.data.challenges[0].status")).isEqualTo("COMPLETED");
        // 스냅샷에 없는 값은 지어내지 않고 null 로 둔다
        assertThat((Integer) read(res, "$.data.challenges[0].weeklyCount")).isNull();
        assertThat((String) read(res, "$.data.challenges[0].mode")).isNull();
    }

    // ===== 헬퍼 =====

    private MvcResult list(Member me, String query) throws Exception {
        MvcResult res = getAuth("/api/v1/challenges" + (query == null ? "" : query), me.token());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res;
    }

    private List<String> idsOf(MvcResult res) throws Exception {
        return read(res, "$.data.challenges[*].challengeId");
    }

    private List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    /** 내가 방장으로 들어가 있는 방 하나. 종료일을 흩어 두어 정렬 키(종료일)가 겹치지 않게 한다. */
    private UUID joined(Member me, String status) {
        UUID challengeId = insertChallenge(me.id(), "EXERCISE", status, "GROUP");
        insertActiveMembership(challengeId, me.id(), "OWNER");
        jdbcTemplate.update("UPDATE challenges SET end_date = DATE_ADD(CURDATE(), INTERVAL ? DAY) WHERE id = ?",
                (int) (Math.abs(challengeId.getLeastSignificantBits()) % 90), bytes(challengeId));
        return challengeId;
    }

    private void leave(UUID challengeId, Member me) {
        jdbcTemplate.update("UPDATE challenge_members SET status='LEFT', left_type='LEAVE', left_at=NOW(6) " +
                "WHERE challenge_id=? AND user_id=?", bytes(challengeId), bytes(me.id()));
    }

    /** 삭제 배치가 하는 일 — 이력 적재 후 방·멤버 행 제거. */
    private void archiveAndDelete(UUID challengeId, Member me) {
        jdbcTemplate.update("INSERT INTO challenge_history " +
                        "(challenge_id, title_snapshot, image_snapshot, category, start_date, end_date, deleted_at) " +
                        "VALUES (?, '삭제된 방 제목', NULL, 'EXERCISE', CURDATE(), CURDATE(), NOW(6))",
                bytes(challengeId));
        jdbcTemplate.update("INSERT INTO challenge_member_history " +
                        "(challenge_id, user_id, final_role, left_type, left_at, final_success_rate) " +
                        "VALUES (?, ?, 'OWNER', 'ACTIVE_AT_DELETE', NULL, NULL)",
                bytes(challengeId), bytes(me.id()));
        jdbcTemplate.update("DELETE FROM challenge_members WHERE challenge_id=?", bytes(challengeId));
        jdbcTemplate.update("DELETE FROM challenges WHERE id=?", bytes(challengeId));
    }
}
