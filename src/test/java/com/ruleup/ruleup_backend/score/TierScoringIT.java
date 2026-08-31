package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.score.domain.IncidentType;
import com.ruleup.ruleup_backend.score.domain.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 티어·점수 엔진 — 점수 및 티어 정책 §4 + 티어·점수 백엔드 테크 스펙.
 *
 * <p>엔진의 설계 전제는 <b>"반영 누계는 카운트만의 함수"</b>다. 그래서 이 테스트는 이벤트를 흘려보내는
 * 대신 판정 원본(VerificationDaily)을 심고 정산을 다시 돌린다 — 실제 운영 경로도 같은 모양이다.
 * 성공에는 별도 도메인 이벤트가 없고 확정 경로가 여러 곳이라, 이벤트를 잡는 대신 원본에서 카운트를
 * 다시 세는 편이 누락도 중복도 없다.
 *
 * <p>확인하는 성질이 넷이다.
 * <ol>
 *   <li><b>멱등</b> — 같은 정산을 두 번 돌려도 점수가 두 배가 되지 않는다</li>
 *   <li><b>한도</b> — 사이클 순변동이 ±20 을 넘지 않고, 한도에 닿았다 되돌아오는 구간이 정확하다</li>
 *   <li><b>경계</b> — 0점·2,000점에서 잘린 감점·획득이 한도를 소비하지 않는다</li>
 *   <li><b>소급</b> — 이의 인용으로 판정이 뒤집히면 점수·티어가 그 자리로 되돌아간다</li>
 * </ol>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TierScoringIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ScoreService scoreService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    // ===== 픽스처 =====

    /**
     * 주 {@code weeklyCount} 회 챌린지 1건 + ACTIVE 멤버십. 시작일을 {@code startedDaysAgo} 일 전으로
     * 밀어 사이클 경계를 만든다. 요일 지정 없이 매일이 인증 가능일이도록 7요일 전부 켠다.
     */
    private UUID challengeWith(UUID ownerId, int weeklyCount, int startedDaysAgo) {
        UUID id = insertChallenge(ownerId, "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(id, ownerId, "OWNER");
        jdbc().update("UPDATE challenges SET weekly_count = ?, " +
                        " start_date = DATE_SUB(start_date, INTERVAL ? DAY) WHERE id = ?",
                weeklyCount, startedDaysAgo, bytes(id));
        return id;
    }

    private UUID memberIdOf(UUID challengeId, UUID userId) {
        return jdbc().queryForObject("SELECT id FROM challenge_members WHERE challenge_id = ? AND user_id = ?",
                (rs, i) -> uuid(rs.getBytes(1)), bytes(challengeId), bytes(userId));
    }

    /** 사이클 {@code cycleNo} 의 {@code dayInCycle}(0-based) 일차에 확정 판정 1건. */
    private UUID judge(UUID challengeId, UUID userId, int cycleNo, int dayInCycle,
                       String status, String verifiedVia) {
        UUID dailyId = UUID.randomUUID();
        int offset = (cycleNo - 1) * 7 + dayInCycle;
        jdbc().update("INSERT INTO VerificationDaily " +
                        "(id, challengeMemberId, challengeId, userId, targetDate, status, verifiedVia, verifiedAt) " +
                        "SELECT ?, ?, ?, ?, DATE_ADD(c.start_date, INTERVAL ? DAY), ?, ?, NOW(3) " +
                        "FROM challenges c WHERE c.id = ?",
                bytes(dailyId), bytes(memberIdOf(challengeId, userId)), bytes(challengeId), bytes(userId),
                offset, status, verifiedVia, bytes(challengeId));
        return dailyId;
    }

    private UUID judge(UUID challengeId, UUID userId, int cycleNo, int dayInCycle, String status) {
        return judge(challengeId, userId, cycleNo, dayInCycle, status, "AUTO");
    }

    /**
     * 사이클 7일을 전부 실패로 확정한다 — <b>전량 미달</b>을 만드는 유일한 방법이다.
     * 주 1회 루틴은 인증 가능일이 7일이라 하루 실패해도 남은 6일로 만회할 수 있어, 마지막 날까지
     * 판정이 끝나야 비로소 미달 1회가 확정된다(정책 §4.4 "만회가 불가능해지는 시점").
     */
    private void judgeAllFailed(UUID challengeId, UUID userId, int cycleNo) {
        for (int d = 0; d < 7; d++) judge(challengeId, userId, cycleNo, d, "FAILED");
    }

    private void setScore(UUID userId, long score, Tier actual, Tier display) {
        jdbc().update("UPDATE user_score_summaries SET total_score = ?, actual_tier = ?, display_tier = ? " +
                "WHERE user_id = ?", score, actual.name(), display.name(), bytes(userId));
    }

    private long scoreOf(UUID userId) {
        Long v = jdbc().queryForObject("SELECT total_score FROM user_score_summaries WHERE user_id = ?",
                Long.class, bytes(userId));
        return v == null ? 0 : v;
    }

    private String tierOf(UUID userId, String column) {
        return jdbc().queryForObject("SELECT " + column + " FROM user_score_summaries WHERE user_id = ?",
                String.class, bytes(userId));
    }

    private Map<String, Object> cycleState(UUID userId, UUID challengeId, int cycleNo) {
        return jdbc().queryForMap("SELECT * FROM cycle_score_states " +
                        "WHERE user_id = ? AND challenge_id = ? AND cycle_no = ?",
                bytes(userId), bytes(challengeId), cycleNo);
    }

    private List<Map<String, Object>> ledger(UUID userId) {
        return jdbc().queryForList("SELECT reason, raw_delta, limited_delta, applied_delta, balance_after, " +
                "incident_type FROM score_transactions WHERE user_id = ? ORDER BY created_at, id", bytes(userId));
    }

    private static UUID uuid(byte[] b) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b);
        return new UUID(bb.getLong(), bb.getLong());
    }

    // ================================================================
    @Nested
    @DisplayName("날짜별 판정 반영")
    class DailyJudgement {

        @Test
        @DisplayName("브론즈 주 7회 — 성공 1건이면 f(1)=1점, 총점 11")
        void firstSuccess() throws Exception {
            Member me = member("sc-first");
            UUID ch = challengeWith(me.id(), 7, 0);
            judge(ch, me.id(), 1, 0, "SUCCESS");

            scoreService.reconcileCycle(me.id(), ch, 1);

            assertThat(scoreOf(me.id())).isEqualTo(11);   // 가입 10 + 1
            assertThat(cycleState(me.id(), ch, 1)).containsEntry("success_count", 1);
        }

        @Test
        @DisplayName("같은 정산을 두 번 돌려도 점수가 두 배가 되지 않는다 — 카운트의 함수라서")
        void idempotent() throws Exception {
            Member me = member("sc-idem");
            UUID ch = challengeWith(me.id(), 7, 0);
            for (int d = 0; d < 3; d++) judge(ch, me.id(), 1, d, "SUCCESS");

            scoreService.reconcileCycle(me.id(), ch, 1);
            long once = scoreOf(me.id());
            scoreService.reconcileCycle(me.id(), ch, 1);
            scoreService.reconcileCycle(me.id(), ch, 1);

            assertThat(scoreOf(me.id())).isEqualTo(once);
        }

        @Test
        @DisplayName("주 7회는 하루 실패하면 그날 바로 미달 1회 확정 — 만회할 날이 없다")
        void dailyRoutineMissIsImmediate() throws Exception {
            Member me = member("sc-miss7");
            UUID ch = challengeWith(me.id(), 7, 0);
            judge(ch, me.id(), 1, 0, "FAILED");

            scoreService.reconcileCycle(me.id(), ch, 1);

            // 브론즈 미달축 W=4, N=7 → f(1) = ⌊(2·4·1+7)/14⌋ = 1
            assertThat(scoreOf(me.id())).isEqualTo(9);
            assertThat(cycleState(me.id(), ch, 1)).containsEntry("miss_count", 1);
        }

        @Test
        @DisplayName("주 N회 유연 루틴은 남은 날로 만회할 수 있는 동안 차감하지 않는다")
        void flexibleRoutineDefersMiss() throws Exception {
            Member me = member("sc-flex");
            UUID ch = challengeWith(me.id(), 5, 0);   // 주 5회, 인증 가능일은 7일
            judge(ch, me.id(), 1, 0, "FAILED");
            judge(ch, me.id(), 1, 1, "FAILED");

            scoreService.reconcileCycle(me.id(), ch, 1);

            // 목표 5 − 성공 0 − 남은 5일 = 0 → 아직 확정 미달이 없다.
            assertThat(cycleState(me.id(), ch, 1)).containsEntry("miss_count", 0);
            assertThat(scoreOf(me.id())).isEqualTo(10);
        }

        @Test
        @DisplayName("만회가 수학적으로 불가능해지는 순간 미달이 확정된다")
        void flexibleRoutineConfirmsWhenUnrecoverable() throws Exception {
            Member me = member("sc-flex2");
            UUID ch = challengeWith(me.id(), 5, 0);
            for (int d = 0; d < 3; d++) judge(ch, me.id(), 1, d, "FAILED");

            scoreService.reconcileCycle(me.id(), ch, 1);

            // 목표 5 − 성공 0 − 남은 4일 = 1 → 미달 1회 확정.
            assertThat(cycleState(me.id(), ch, 1)).containsEntry("miss_count", 1);
        }

        @Test
        @DisplayName("수동 인증은 점수에 반영하지 않는다 — 통계에는 들어가지만 티어는 자동 인증만 센다")
        void manualVerificationIsNotScored() throws Exception {
            Member me = member("sc-manual");
            UUID ch = challengeWith(me.id(), 7, 0);
            judge(ch, me.id(), 1, 0, "SUCCESS", "MANUAL");
            judge(ch, me.id(), 1, 1, "SUCCESS", "MANUAL");

            scoreService.reconcileCycle(me.id(), ch, 1);

            assertThat(scoreOf(me.id())).isEqualTo(10);
            assertThat(cycleState(me.id(), ch, 1)).containsEntry("success_count", 0);
        }

        @Test
        @DisplayName("배점 티어는 사이클 시작 시점의 실제 티어로 고정된다 — 주중 승급이 배점을 바꾸지 않는다")
        void tierSnapshotIsFixedAtCycleStart() throws Exception {
            Member me = member("sc-snapshot");
            setScore(me.id(), 95, Tier.BRONZE, Tier.BRONZE);
            UUID ch = challengeWith(me.id(), 7, 0);

            judge(ch, me.id(), 1, 0, "SUCCESS");
            scoreService.reconcileCycle(me.id(), ch, 1);
            assertThat(cycleState(me.id(), ch, 1)).containsEntry("tier_snapshot", "BRONZE");

            // 승급시킨 뒤에도 이 사이클의 배점 티어는 브론즈 그대로다.
            setScore(me.id(), 150, Tier.SILVER, Tier.SILVER);
            for (int d = 1; d < 7; d++) judge(ch, me.id(), 1, d, "SUCCESS");
            scoreService.reconcileCycle(me.id(), ch, 1);

            assertThat(cycleState(me.id(), ch, 1)).containsEntry("tier_snapshot", "BRONZE");
            // 브론즈 주 7회 전량 성공 = f(7) = W = +10. 첫 반영 +1 뒤 +9 가 더 붙는다.
            assertThat(scoreOf(me.id())).isEqualTo(159);
        }
    }

    // ================================================================
    @Nested
    @DisplayName("사이클 순변동 ±20 한도")
    class CycleSwingLimit {

        @Test
        @DisplayName("루비 주 1회 전량 미달(이론 −38)은 −20 으로 잘린다")
        void rubyFullMissClamped() throws Exception {
            Member me = member("sc-ruby");
            setScore(me.id(), 1200, Tier.RUBY, Tier.RUBY);
            UUID ch = challengeWith(me.id(), 1, 0);
            judgeAllFailed(ch, me.id(), 1);

            scoreService.reconcileCycle(me.id(), ch, 1);

            assertThat(scoreOf(me.id())).isEqualTo(1180);   // −38 이 아니라 −20
            Map<String, Object> state = cycleState(me.id(), ch, 1);
            assertThat(state).containsEntry("raw_cumulative", -38)      // 원점수는 전액 남긴다
                    .containsEntry("limited_cumulative", -20);
        }

        @Test
        @DisplayName("원장에 원점수·한도값·실반영량 셋을 모두 남긴다 — 감사와 재계산의 근거다")
        void ledgerKeepsAllThree() throws Exception {
            Member me = member("sc-ledger");
            setScore(me.id(), 1200, Tier.RUBY, Tier.RUBY);
            UUID ch = challengeWith(me.id(), 1, 0);
            judgeAllFailed(ch, me.id(), 1);

            scoreService.reconcileCycle(me.id(), ch, 1);

            assertThat(ledger(me.id())).singleElement()
                    .satisfies(row -> assertThat(row)
                            .containsEntry("reason", "CONFIRMED_MISS")
                            .containsEntry("raw_delta", -38)
                            .containsEntry("limited_delta", -20)
                            .containsEntry("applied_delta", -20)
                            .containsEntry("balance_after", 1180));
        }

        @Test
        @DisplayName("한도는 챌린지·사이클마다 따로다 — 병행하면 계정 하락이 20점을 넘을 수 있다")
        void limitIsPerCycleNotPerAccount() throws Exception {
            Member me = member("sc-parallel");
            setScore(me.id(), 1200, Tier.RUBY, Tier.RUBY);
            UUID a = challengeWith(me.id(), 1, 0);
            UUID b = challengeWith(me.id(), 1, 0);
            judgeAllFailed(a, me.id(), 1);
            judgeAllFailed(b, me.id(), 1);

            scoreService.reconcileCycle(me.id(), a, 1);
            scoreService.reconcileCycle(me.id(), b, 1);

            assertThat(scoreOf(me.id())).isEqualTo(1160);   // −20 × 2. 계정 합산 한도는 두지 않는다
        }

        @Test
        @DisplayName("0점에서 반영되지 않은 감점은 한도를 소비하지 않는다")
        void zeroFloorDoesNotConsumeLimit() throws Exception {
            Member me = member("sc-floor");
            setScore(me.id(), 0, Tier.BRONZE, Tier.BRONZE);
            UUID ch = challengeWith(me.id(), 1, 0);
            judgeAllFailed(ch, me.id(), 1);

            scoreService.reconcileCycle(me.id(), ch, 1);

            assertThat(scoreOf(me.id())).isZero();
            Map<String, Object> state = cycleState(me.id(), ch, 1);
            assertThat(state).containsEntry("raw_cumulative", -4)
                    .containsEntry("limited_cumulative", 0);   // 한도를 쓰지 않았다
        }

        @Test
        @DisplayName("2,000점에서 추가 획득은 실제 적용량 0으로 기록한다")
        void ceiling() throws Exception {
            Member me = member("sc-ceiling");
            setScore(me.id(), 2000, Tier.RUBY, Tier.RUBY);
            UUID ch = challengeWith(me.id(), 1, 0);
            judge(ch, me.id(), 1, 0, "SUCCESS");

            scoreService.reconcileCycle(me.id(), ch, 1);

            assertThat(scoreOf(me.id())).isEqualTo(2000);
            assertThat(ledger(me.id())).singleElement()
                    .satisfies(row -> assertThat(row).containsEntry("applied_delta", 0));
        }
    }

    // ================================================================
    @Nested
    @DisplayName("사이클 마감 · 연속 기록")
    class CycleClose {

        @Test
        @DisplayName("목표 100% 충족이면 사이클 성공 — 연속 성공이 오르고 실패 연속은 0으로 초기화된다")
        void success() throws Exception {
            Member me = member("sc-close-ok");
            UUID ch = challengeWith(me.id(), 3, 14);
            for (int d = 0; d < 3; d++) judge(ch, me.id(), 1, d, "SUCCESS");
            for (int d = 3; d < 7; d++) judge(ch, me.id(), 1, d, "FAILED");

            scoreService.reconcileCycle(me.id(), ch, 1);
            scoreService.closeCycle(me.id(), ch, 1);

            assertThat(cycleState(me.id(), ch, 1)).containsEntry("cycle_result", "SUCCESS");
            assertThat(jdbc().queryForObject("SELECT success_streak FROM challenge_streaks " +
                            "WHERE user_id = ? AND challenge_id = ?", Integer.class,
                    bytes(me.id()), bytes(ch))).isEqualTo(1);
        }

        @Test
        @DisplayName("달성률 50% 이하면 사이클 실패 — 연속 실패가 오른다(방 내부 모듈의 강퇴 입력)")
        void failure() throws Exception {
            Member me = member("sc-close-fail");
            UUID ch = challengeWith(me.id(), 4, 14);
            for (int d = 0; d < 2; d++) judge(ch, me.id(), 1, d, "SUCCESS");
            for (int d = 2; d < 7; d++) judge(ch, me.id(), 1, d, "FAILED");

            scoreService.reconcileCycle(me.id(), ch, 1);
            scoreService.closeCycle(me.id(), ch, 1);

            // 성공 2 × 2 = 4 > 목표 4 가 아니다 → 정확히 50% 는 실패다.
            assertThat(cycleState(me.id(), ch, 1)).containsEntry("cycle_result", "FAILURE");
            assertThat(jdbc().queryForObject("SELECT failure_streak FROM challenge_streaks " +
                            "WHERE user_id = ? AND challenge_id = ?", Integer.class,
                    bytes(me.id()), bytes(ch))).isEqualTo(1);
        }

        @Test
        @DisplayName("부분 달성은 연속 성공을 유지하고 실패 연속만 초기화한다")
        void partial() throws Exception {
            Member me = member("sc-close-partial");
            UUID ch = challengeWith(me.id(), 4, 21);
            // 1주차 성공으로 연속 성공 1을 만든다.
            for (int d = 0; d < 4; d++) judge(ch, me.id(), 1, d, "SUCCESS");
            scoreService.reconcileCycle(me.id(), ch, 1);
            scoreService.closeCycle(me.id(), ch, 1);

            // 2주차는 3/4 → 부분 달성.
            for (int d = 0; d < 3; d++) judge(ch, me.id(), 2, d, "SUCCESS");
            for (int d = 3; d < 7; d++) judge(ch, me.id(), 2, d, "FAILED");
            scoreService.reconcileCycle(me.id(), ch, 2);
            scoreService.closeCycle(me.id(), ch, 2);

            assertThat(cycleState(me.id(), ch, 2)).containsEntry("cycle_result", "PARTIAL");
            assertThat(jdbc().queryForObject("SELECT success_streak FROM challenge_streaks " +
                            "WHERE user_id = ? AND challenge_id = ?", Integer.class,
                    bytes(me.id()), bytes(ch))).isEqualTo(1);   // 유지 — 오르지도 끊기지도 않는다
        }

        @Test
        @DisplayName("2사이클 연속 성공이면 보너스 +1 이 사이클 한도 안에서 붙는다")
        void streakBonus() throws Exception {
            Member me = member("sc-bonus");
            UUID ch = challengeWith(me.id(), 7, 21);
            for (int c = 1; c <= 2; c++) {
                for (int d = 0; d < 7; d++) judge(ch, me.id(), c, d, "SUCCESS");
                scoreService.reconcileCycle(me.id(), ch, c);
                scoreService.closeCycle(me.id(), ch, c);
            }
            // 브론즈 주 7회 전량 성공 = +10/사이클. 2사이클째에 보너스 +1.
            assertThat(scoreOf(me.id())).isEqualTo(10 + 10 + 10 + 1);
            assertThat(ledger(me.id())).anySatisfy(row ->
                    assertThat(row).containsEntry("reason", "STREAK_BONUS").containsEntry("raw_delta", 1));
        }

        @Test
        @DisplayName("사이클 마감은 멱등하다 — 두 번 닫아도 보너스가 두 번 붙지 않는다")
        void closeIsIdempotent() throws Exception {
            Member me = member("sc-close-idem");
            UUID ch = challengeWith(me.id(), 7, 14);
            for (int d = 0; d < 7; d++) judge(ch, me.id(), 1, d, "SUCCESS");
            scoreService.reconcileCycle(me.id(), ch, 1);

            scoreService.closeCycle(me.id(), ch, 1);
            long once = scoreOf(me.id());
            scoreService.closeCycle(me.id(), ch, 1);

            assertThat(scoreOf(me.id())).isEqualTo(once);
        }
    }

    // ================================================================
    @Nested
    @DisplayName("사건성 감점 (정책 §4.8)")
    class Incidents {

        @Test
        @DisplayName("부정행위 검출은 −50 이고 사이클 한도를 거치지 않는다")
        void cheat() throws Exception {
            Member me = member("sc-cheat");
            setScore(me.id(), 300, Tier.GOLD, Tier.GOLD);
            UUID ch = challengeWith(me.id(), 7, 0);

            scoreService.applyIncident(me.id(), ch, IncidentType.CHEAT_DETECTED, "cheat-1", 0);

            assertThat(scoreOf(me.id())).isEqualTo(250);   // −20 이 아니라 −50 전액
            assertThat(ledger(me.id())).singleElement().satisfies(row -> assertThat(row)
                    .containsEntry("reason", "INCIDENT")
                    .containsEntry("incident_type", "CHEAT_DETECTED")
                    .containsEntry("raw_delta", -50)
                    .containsEntry("limited_delta", -50));
        }

        @Test
        @DisplayName("사건성 감점은 사이클 한도 상태를 건드리지 않는다")
        void incidentDoesNotTouchCycleState() throws Exception {
            Member me = member("sc-incident-cycle");
            setScore(me.id(), 300, Tier.GOLD, Tier.GOLD);
            UUID ch = challengeWith(me.id(), 7, 0);
            judge(ch, me.id(), 1, 0, "SUCCESS");
            scoreService.reconcileCycle(me.id(), ch, 1);
            Object rawBefore = cycleState(me.id(), ch, 1).get("raw_cumulative");

            scoreService.applyIncident(me.id(), ch, IncidentType.CHEAT_DETECTED, "cheat-2", 0);

            assertThat(cycleState(me.id(), ch, 1)).containsEntry("raw_cumulative", rawBefore);
        }

        @Test
        @DisplayName("권한 미허용 강퇴는 −15")
        void permissionKick() throws Exception {
            Member me = member("sc-permkick");
            setScore(me.id(), 300, Tier.GOLD, Tier.GOLD);
            UUID ch = challengeWith(me.id(), 7, 0);

            scoreService.applyIncident(me.id(), ch, IncidentType.PERMISSION_KICK, "kick-1", 0);

            assertThat(scoreOf(me.id())).isEqualTo(285);
        }

        @Test
        @DisplayName("중도 탈퇴는 진행 기간에 비례해 줄고, 1년 이상 성공했으면 면제된다")
        void voluntaryLeave() throws Exception {
            Member fresh = member("sc-leave-new");
            setScore(fresh.id(), 300, Tier.GOLD, Tier.GOLD);
            UUID a = challengeWith(fresh.id(), 7, 0);
            scoreService.applyIncident(fresh.id(), a, IncidentType.VOLUNTARY_LEAVE, "leave-1", 0);
            assertThat(scoreOf(fresh.id())).isEqualTo(285);   // 15 × (1 − 0/52) = 15

            Member half = member("sc-leave-half");
            setScore(half.id(), 300, Tier.GOLD, Tier.GOLD);
            UUID b = challengeWith(half.id(), 7, 0);
            scoreService.applyIncident(half.id(), b, IncidentType.VOLUNTARY_LEAVE, "leave-2", 26);
            assertThat(scoreOf(half.id())).isEqualTo(292);    // ⌈15 × 0.5⌉ = 8

            Member veteran = member("sc-leave-year");
            setScore(veteran.id(), 300, Tier.GOLD, Tier.GOLD);
            UUID c = challengeWith(veteran.id(), 7, 0);
            scoreService.applyIncident(veteran.id(), c, IncidentType.VOLUNTARY_LEAVE, "leave-3", 52);
            assertThat(scoreOf(veteran.id())).isEqualTo(300); // 1년 이상이면 면제
        }

        @Test
        @DisplayName("같은 사건을 두 번 전달해도 한 번만 반영한다")
        void idempotent() throws Exception {
            Member me = member("sc-incident-idem");
            setScore(me.id(), 300, Tier.GOLD, Tier.GOLD);
            UUID ch = challengeWith(me.id(), 7, 0);

            scoreService.applyIncident(me.id(), ch, IncidentType.CHEAT_DETECTED, "dup", 0);
            scoreService.applyIncident(me.id(), ch, IncidentType.CHEAT_DETECTED, "dup", 0);

            assertThat(scoreOf(me.id())).isEqualTo(250);
            assertThat(ledger(me.id())).hasSize(1);
        }

        @Test
        @DisplayName("연속 실패 강퇴에는 감점이 없다 — 각 주의 루틴 점수에 이미 반영돼 있다")
        void consecutiveFailureKickHasNoDeduction() {
            assertThat(IncidentType.values())
                    .noneSatisfy(t -> assertThat(t.name()).contains("CONSECUTIVE_FAILURE"));
        }
    }

    // ================================================================
    @Nested
    @DisplayName("티어 전이")
    class TierTransition {

        @Test
        @DisplayName("승급은 즉시고 초과 점수를 버리지 않는다 — 98 +4 = 실버 102")
        void promotion() throws Exception {
            Member me = member("sc-promote");
            setScore(me.id(), 98, Tier.BRONZE, Tier.BRONZE);
            UUID ch = challengeWith(me.id(), 1, 0);   // 브론즈 주 1회 성공 = +10
            judge(ch, me.id(), 1, 0, "SUCCESS");

            scoreService.reconcileCycle(me.id(), ch, 1);

            assertThat(scoreOf(me.id())).isEqualTo(108);
            assertThat(tierOf(me.id(), "actual_tier")).isEqualTo("SILVER");
            assertThat(tierOf(me.id(), "display_tier")).isEqualTo("SILVER");
        }

        @Test
        @DisplayName("유예 구간에서는 실제 티어만 내려가고 표시 티어는 버틴다")
        void graceBand() throws Exception {
            Member me = member("sc-grace");
            setScore(me.id(), 305, Tier.GOLD, Tier.GOLD);
            UUID ch = challengeWith(me.id(), 1, 0);   // 골드 주 1회 전량 미달 = −14
            judgeAllFailed(ch, me.id(), 1);

            scoreService.reconcileCycle(me.id(), ch, 1);

            assertThat(scoreOf(me.id())).isEqualTo(291);
            assertThat(tierOf(me.id(), "actual_tier")).isEqualTo("SILVER");
            assertThat(tierOf(me.id(), "display_tier")).isEqualTo("GOLD");   // 280 이상이라 유지
        }

        @Test
        @DisplayName("유예 하한을 넘어서면 강등이 확정된다")
        void demotion() throws Exception {
            Member me = member("sc-demote");
            setScore(me.id(), 285, Tier.SILVER, Tier.GOLD);   // 이미 유예 중
            UUID ch = challengeWith(me.id(), 1, 0);
            judgeAllFailed(ch, me.id(), 1);                  // 골드 배점이 아니라 실제 티어(실버) 배점 −8

            scoreService.reconcileCycle(me.id(), ch, 1);

            assertThat(scoreOf(me.id())).isEqualTo(277);      // 279 이하 → 강등 확정
            assertThat(tierOf(me.id(), "display_tier")).isEqualTo("SILVER");
        }

        @Test
        @DisplayName("사건성 감점으로 유예를 한 번에 관통하면 실제 티어까지 즉시 내려간다")
        void bigDropSkipsGrace() throws Exception {
            Member me = member("sc-bigdrop");
            setScore(me.id(), 305, Tier.GOLD, Tier.GOLD);
            UUID ch = challengeWith(me.id(), 7, 0);

            scoreService.applyIncident(me.id(), ch, IncidentType.CHEAT_DETECTED, "big", 0);

            assertThat(scoreOf(me.id())).isEqualTo(255);
            assertThat(tierOf(me.id(), "display_tier")).isEqualTo("SILVER");
        }
    }

    // ================================================================
    @Nested
    @DisplayName("이의 인용에 따른 소급 정정 (정책 §4.10)")
    class Correction {

        @Test
        @DisplayName("실패가 성공으로 뒤집히면 감점이 전액 복원되고 성공 점수가 지급된다")
        void restoresFully() throws Exception {
            Member me = member("sc-appeal");
            UUID ch = challengeWith(me.id(), 7, 7);
            UUID failed = judge(ch, me.id(), 1, 0, "FAILED");
            for (int d = 1; d < 7; d++) judge(ch, me.id(), 1, d, "SUCCESS");
            scoreService.reconcileCycle(me.id(), ch, 1);
            long beforeAppeal = scoreOf(me.id());

            // 이의 인용 — 판정 원본이 성공으로 정정된다.
            jdbc().update("UPDATE VerificationDaily SET status = 'SUCCESS', failureReason = NULL WHERE id = ?",
                    bytes(failed));
            scoreService.recompute(me.id(), ch, 1, failed);

            // 전량 성공이 되어 브론즈 주 7회 총 배점 +10 이 된다(부분 복원이 아니다).
            assertThat(scoreOf(me.id())).isEqualTo(20).isGreaterThan(beforeAppeal);
            assertThat(cycleState(me.id(), ch, 1))
                    .containsEntry("success_count", 7).containsEntry("miss_count", 0);
        }

        @Test
        @DisplayName("정정 전 기록을 지우지 않고 관계를 남긴다")
        void keepsCorrectionTrail() throws Exception {
            Member me = member("sc-appeal-trail");
            UUID ch = challengeWith(me.id(), 7, 7);
            UUID failed = judge(ch, me.id(), 1, 0, "FAILED");
            scoreService.reconcileCycle(me.id(), ch, 1);

            jdbc().update("UPDATE VerificationDaily SET status = 'SUCCESS' WHERE id = ?", bytes(failed));
            scoreService.recompute(me.id(), ch, 1, failed);

            assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM score_corrections WHERE user_id = ?",
                    Integer.class, bytes(me.id()))).isEqualTo(1);
            // 원장의 옛 감점 행도 남아 있다 — 덮어쓰지 않는다.
            assertThat(ledger(me.id())).anySatisfy(row ->
                    assertThat(row).containsEntry("reason", "CONFIRMED_MISS"));
        }

        @Test
        @DisplayName("같은 판정을 두 번 정정해도 점수가 두 번 오르지 않는다")
        void idempotent() throws Exception {
            Member me = member("sc-appeal-idem");
            UUID ch = challengeWith(me.id(), 7, 7);
            UUID failed = judge(ch, me.id(), 1, 0, "FAILED");
            scoreService.reconcileCycle(me.id(), ch, 1);

            jdbc().update("UPDATE VerificationDaily SET status = 'SUCCESS' WHERE id = ?", bytes(failed));
            scoreService.recompute(me.id(), ch, 1, failed);
            long once = scoreOf(me.id());
            scoreService.recompute(me.id(), ch, 1, failed);

            assertThat(scoreOf(me.id())).isEqualTo(once);
        }
    }

    // ================================================================
    @Nested
    @DisplayName("매너 온도 잔재")
    class MannerTemperatureRemoved {

        @Test
        @DisplayName("매너 온도 테이블은 남아 있지 않다")
        void tablesAreGone() {
            List<String> gone = List.of("ReputationScore", "ReputationSnapshot", "Milestone");
            for (String table : gone) {
                Integer count = jdbc().queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables " +
                                "WHERE table_schema = DATABASE() AND table_name = ?", Integer.class, table);
                assertThat(count).as("%s 는 삭제돼야 한다", table).isZero();
            }
        }

        @Test
        @DisplayName("챌린지에 최소 매너 온도가 남아 있지 않다 — 게이팅은 표시 티어로 한다")
        void challengeHasNoMannerGate() {
            Integer count = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                            "WHERE table_schema = DATABASE() AND table_name = 'challenges' " +
                            "AND column_name = 'min_manner_temperature'", Integer.class);
            assertThat(count).isZero();
        }
    }
}
