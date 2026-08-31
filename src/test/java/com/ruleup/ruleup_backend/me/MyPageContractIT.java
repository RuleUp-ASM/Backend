package com.ruleup.ruleup_backend.me;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 마이페이지 · 프로필 화면 — 테크 스펙 공통(2026-08-31) + API 명세 모음 '마이페이지' 모듈 10건.
 *
 * <p>이 모듈은 <b>조회 전용 조립 계층</b>이다. 판정도 점수 계산도 하지 않고 인증·티어가 쌓아둔 값을
 * 화면 단위로 엮을 뿐이라, 테스트가 지키는 것도 대부분 <b>계약</b>이다 — 어떤 필드가 있고, 어떤 필드가
 * 응답에 <b>없어야</b> 하는가.
 *
 * <p>구 체계에서 갈아엎히는 지점이 셋이다.
 * <ol>
 *   <li><b>매너 온도 → 티어</b>: {@code /me/reputation} · {@code /me/reputation/history} 가
 *       {@code /me/tier} · {@code /me/tier/history} 로 전면 대체된다. 점수는 티어마다 0~99 로 끊지 않고
 *       <b>계정당 하나의 단일 축 0~2,000</b> 이다(정책 §1.1, 2026-08-26).</li>
 *   <li><b>통계 5종 → 4종</b>: 최근 12주 사이클 성과가 정책 §3에서 삭제됐고, 기간 파라미터도 없다.
 *       {@code weeklyScoreDelta} 는 '계정 주간'이라는 단위 자체가 사라져 폐기됐다.</li>
 *   <li><b>이의는 구제권이 아니다</b>: 횟수 한도가 없으므로 잔여 구제권·계류·기각이 존재하지 않는다.</li>
 * </ol>
 *
 * <p>공개 범위는 <b>서버가 강제</b>한다 — 타인 프로필에서 점수·통계·캘린더는 값이 가려지는 게 아니라
 * 응답에 필드가 없어야 한다(기능 스펙 노출 0건 가드레일).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MyPageContractIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    // ===== 픽스처 =====

    /** 점수 요약을 원하는 값으로 덮어쓴다 — 가입 시 브론즈 10점으로 만들어져 있다. */
    private void setScore(UUID userId, long score, String actualTier, String displayTier) {
        jdbc().update("UPDATE user_score_summaries SET total_score = ?, actual_tier = ?, display_tier = ? " +
                "WHERE user_id = ?", score, actualTier, displayTier, bytes(userId));
    }

    /**
     * 점수 변동 1건. {@code reason} 은 <b>저장</b> 사건이고 화면 표기는 서버가 매핑한다.
     * daysAgo 는 KST 달력이 아니라 실제 경과 시간이라 UTC 로 빼도 된다.
     */
    private void insertScoreEvent(UUID userId, UUID challengeId, String reason, String incidentType,
                                  long delta, long balanceAfter, int daysAgo) {
        jdbc().update("INSERT INTO score_transactions " +
                        "(id, user_id, raw_delta, limited_delta, applied_delta, cycle_limit_applied, " +
                        " balance_after, reason, challenge_id, cycle_no, incident_type, " +
                        " idempotency_key, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, 1, ?, ?, DATE_SUB(NOW(3), INTERVAL ? DAY))",
                bytes(UUID.randomUUID()), bytes(userId), delta, delta, delta, balanceAfter, reason,
                challengeId == null ? null : bytes(challengeId), incidentType, uniq("idem"), daysAgo);
    }

    private void insertScoreEvent(UUID userId, UUID challengeId, String reason,
                                  long delta, long balanceAfter, int daysAgo) {
        insertScoreEvent(userId, challengeId, reason, null, delta, balanceAfter, daysAgo);
    }

    /** 확정된 일 판정 1건(RoutineOutcome = 통계·스트릭의 원천). targetDate 는 KST 달력 날짜다. */
    private void insertOutcome(UUID userId, UUID challengeId, int daysAgo, String status) {
        jdbc().update("INSERT INTO RoutineOutcome " +
                        "(id, userId, challengeId, challengeMemberId, category, targetDate, status, " +
                        " verifiedVia, confirmedAt) " +
                        "VALUES (?, ?, ?, ?, 'EXERCISE', " +
                        " DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY), " +
                        " ?, 'MANUAL', NOW(3))",
                bytes(UUID.randomUUID()), bytes(userId), bytes(challengeId), bytes(UUID.randomUUID()),
                daysAgo, status);
    }

    /** 멤버십 + 그 멤버의 일 판정(VerificationDaily) 1건. 이의 대상 verificationId 를 돌려준다. */
    private UUID insertDaily(UUID challengeId, UUID userId, int daysAgo, String status,
                             boolean appealOpen) {
        // challenge_members 는 (challenge_id, user_id) UNIQUE 라 같은 방에 두 번 넣을 수 없다.
        UUID memberId = jdbc().query(
                "SELECT id FROM challenge_members WHERE challenge_id = ? AND user_id = ?",
                rs -> rs.next() ? uuid(rs.getBytes(1)) : null, bytes(challengeId), bytes(userId));
        if (memberId == null) {
            memberId = UUID.randomUUID();
            jdbc().update("INSERT INTO challenge_members (id, challenge_id, user_id, role, status) " +
                    "VALUES (?, ?, ?, 'MEMBER', 'ACTIVE')", bytes(memberId), bytes(challengeId), bytes(userId));
        }
        UUID dailyId = UUID.randomUUID();
        jdbc().update("INSERT INTO VerificationDaily " +
                        "(id, challengeMemberId, challengeId, userId, targetDate, status, failureReason, " +
                        " appealClosesAt, verifiedAt) " +
                        "VALUES (?, ?, ?, ?, " +
                        " DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY), " +
                        " ?, ?, ?, NOW(3))",
                bytes(dailyId), bytes(memberId), bytes(challengeId), bytes(userId), daysAgo, status,
                "FAILED".equals(status) ? "NO_SIGNAL" : null,
                appealOpen ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().plusDays(1))
                           : java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(1)));
        return dailyId;
    }

    /** 인용된 이의 1건. 접수된 행은 전부 인용된 이의다 — 기각 상태가 없다. */
    private UUID insertAppeal(UUID userId, UUID challengeId, UUID dailyId, String reason, int daysAgo) {
        UUID id = UUID.randomUUID();
        UUID memberId = jdbc().queryForObject(
                "SELECT challengeMemberId FROM VerificationDaily WHERE id = ?",
                (rs, i) -> uuid(rs.getBytes(1)), bytes(dailyId));
        jdbc().update("INSERT INTO verification_appeals " +
                        "(id, verificationDailyId, challengeId, challengeMemberId, userId, targetDate, " +
                        " reason, acceptedAt, createdAt) " +
                        "VALUES (?, ?, ?, ?, ?, " +
                        " DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY), " +
                        " ?, DATE_SUB(NOW(3), INTERVAL ? DAY), DATE_SUB(NOW(3), INTERVAL ? DAY))",
                bytes(id), bytes(dailyId), bytes(challengeId), bytes(memberId), bytes(userId),
                daysAgo, reason, daysAgo, daysAgo);
        return id;
    }

    private static UUID uuid(byte[] b) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b);
        return new UUID(bb.getLong(), bb.getLong());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MvcResult res) throws Exception {
        return read(res, "$.data");
    }

    // ================================================================
    @Nested
    @DisplayName("GET /me/tier — 매너 온도를 전면 대체한다")
    class TierDetail {

        @Test
        @DisplayName("가입 직후엔 브론즈 10점이고, 실버 시작점까지 90점이 남는다")
        void fresh_signup_tier() throws Exception {
            Member me = member("tier-fresh");

            Map<String, Object> d = data(getAuth("/api/v1/me/tier", me.token()));

            assertThat(d).containsEntry("tier", "BRONZE")
                    .containsEntry("score", 10)
                    .containsEntry("displayTier", "BRONZE")
                    .containsEntry("graceBand", false);
            assertThat((Map<String, Object>) d.get("promotion"))
                    .containsEntry("nextTier", "SILVER")
                    .containsEntry("pointsToPromote", 90);
            // 브론즈는 더 내려갈 티어가 없다.
            assertThat(d.get("demotion")).isNull();
        }

        @Test
        @DisplayName("점수는 티어 안에서 0~99 로 끊지 않는다 — 계정당 단일 축 0~2,000")
        void score_is_a_single_axis() throws Exception {
            Member me = member("tier-axis");
            setScore(me.id(), 370, "GOLD", "GOLD");

            Map<String, Object> d = data(getAuth("/api/v1/me/tier", me.token()));

            assertThat(d).containsEntry("score", 370);   // 70 이 아니다
        }

        @Test
        @DisplayName("골드 370점 — 다이아까지 130점, 유예 하한 280 · 강등 확정 279")
        void gold_promotion_and_demotion() throws Exception {
            Member me = member("tier-gold");
            setScore(me.id(), 370, "GOLD", "GOLD");

            Map<String, Object> d = data(getAuth("/api/v1/me/tier", me.token()));

            assertThat((Map<String, Object>) d.get("promotion"))
                    .containsEntry("nextTier", "DIAMOND")
                    .containsEntry("pointsToPromote", 130);
            assertThat((Map<String, Object>) d.get("demotion"))
                    .containsEntry("graceFloor", 280)
                    .containsEntry("demoteAt", 279);
        }

        @Test
        @DisplayName("표시 티어 시작점보다 1~20점 낮으면 유예 구간 — 표시 티어가 유지된다")
        void grace_band() throws Exception {
            Member me = member("tier-grace");
            setScore(me.id(), 285, "SILVER", "GOLD");   // 실제는 실버, 표시는 아직 골드

            Map<String, Object> d = data(getAuth("/api/v1/me/tier", me.token()));

            assertThat(d).containsEntry("tier", "SILVER")
                    .containsEntry("displayTier", "GOLD")
                    .containsEntry("graceBand", true);
        }

        @Test
        @DisplayName("루비는 더 올라갈 곳이 없어 promotion 이 null")
        void ruby_has_no_promotion() throws Exception {
            Member me = member("tier-ruby");
            setScore(me.id(), 1500, "RUBY", "RUBY");

            assertThat(data(getAuth("/api/v1/me/tier", me.token())).get("promotion")).isNull();
        }

        @Test
        @DisplayName("최근 변동은 10건까지 최신순으로 — date·reason·challengeId·delta")
        void recent_changes() throws Exception {
            Member me = member("tier-changes");
            UUID ch = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            for (int i = 0; i < 12; i++) insertScoreEvent(me.id(), ch, "DAILY_SUCCESS", 5, 10 + 5L * i, 12 - i);
            insertScoreEvent(me.id(), ch, "INCIDENT", "CHEAT_DETECTED", -50, 20, 0);

            Map<String, Object> d = data(getAuth("/api/v1/me/tier", me.token()));
            List<Map<String, Object>> changes = (List<Map<String, Object>>) d.get("recentChanges");

            assertThat(changes).hasSize(10);
            assertThat(changes.getFirst())
                    .containsEntry("reason", "CHEAT")
                    .containsEntry("delta", -50)
                    .containsEntry("challengeId", ch.toString());
            assertThat(changes.getFirst().get("date")).asString().matches("\\d{4}-\\d{2}-\\d{2}");
        }

        @Test
        @DisplayName("주간 변동(weeklyDelta)은 내리지 않는다 — 계정 주간이라는 단위가 사라졌다")
        void no_weekly_delta() throws Exception {
            Member me = member("tier-noweekly");
            assertThat(data(getAuth("/api/v1/me/tier", me.token())))
                    .doesNotContainKeys("weeklyDelta", "weeklyScoreDelta", "mannerTemperature", "bandLabel");
        }

        @Test
        @DisplayName("구 매너 온도 경로는 사라졌다")
        void old_reputation_paths_are_gone() throws Exception {
            Member me = member("tier-old");
            assertThat(getAuth("/api/v1/me/reputation", me.token()).getResponse().getStatus()).isEqualTo(404);
            assertThat(getAuth("/api/v1/me/reputation/history", me.token()).getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    // ================================================================
    @Nested
    @DisplayName("GET /me/tier/history — 월말 스냅샷 그래프, 1년 보관")
    class TierHistory {

        @Test
        @DisplayName("월말 스냅샷과 역대 최고를 내리고 하락 사유는 표기하지 않는다")
        void monthly_snapshots() throws Exception {
            Member me = member("hist-basic");
            UUID ch = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            insertScoreEvent(me.id(), ch, "DAILY_SUCCESS", 90, 100, 70);    // 약 2달 전
            insertScoreEvent(me.id(), ch, "DAILY_SUCCESS", 250, 350, 40);   // 약 1달 전 — 역대 최고
            insertScoreEvent(me.id(), ch, "CONFIRMED_MISS", -50, 300, 5);   // 이번 달

            Map<String, Object> d = data(getAuth("/api/v1/me/tier/history", me.token()));

            assertThat(d).containsEntry("retentionNote", "1년 보관");
            Map<String, Object> best = (Map<String, Object>) d.get("best");
            assertThat(best).containsEntry("score", 350).containsEntry("tier", "GOLD");
            assertThat(best.get("date")).asString().matches("\\d{4}-\\d{2}-\\d{2}");

            List<Map<String, Object>> monthly = (List<Map<String, Object>>) d.get("monthly");
            assertThat(monthly).isNotEmpty();
            assertThat(monthly.getFirst()).containsOnlyKeys("month", "endTier", "endScore");
            assertThat(monthly.getLast()).containsEntry("endScore", 300);   // 그 달의 마지막 값
            assertThat(monthly.getFirst().get("month")).asString().matches("\\d{4}-\\d{2}");
            // 그래프 원천이라 마일스톤·피크 피드는 없다.
            assertThat(d).doesNotContainKeys("milestones", "peak", "changes");
        }

        @Test
        @DisplayName("months 는 1~12 만 받는다")
        void months_range() throws Exception {
            Member me = member("hist-range");
            assertThat(getAuth("/api/v1/me/tier/history?months=12", me.token())
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(getAuth("/api/v1/me/tier/history?months=1", me.token())
                    .getResponse().getStatus()).isEqualTo(200);
            expectError(getAuth("/api/v1/me/tier/history?months=13", me.token()), 400, "INVALID_HISTORY_MONTHS");
            expectError(getAuth("/api/v1/me/tier/history?months=0", me.token()), 400, "INVALID_HISTORY_MONTHS");
        }

        @Test
        @DisplayName("1년이 지난 이력은 보관하지 않으므로 조회되지 않는다")
        void retention_one_year() throws Exception {
            Member me = member("hist-retention");
            UUID ch = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            insertScoreEvent(me.id(), ch, "DAILY_SUCCESS", 900, 910, 400);   // 13개월 전 — 역대 최고였지만 만료

            Map<String, Object> d = data(getAuth("/api/v1/me/tier/history", me.token()));

            assertThat((List<?>) d.get("monthly")).isEmpty();
            assertThat(d.get("best")).isNull();
        }
    }

    // ================================================================
    @Nested
    @DisplayName("GET /me/stats — 정책 지표 4종 고정")
    class Stats {

        @Test
        @DisplayName("기간 파라미터가 없고 4종만 내린다 — 12주 성과·온도·인사이트는 폐기")
        void four_metrics_only() throws Exception {
            Member me = member("stats-shape");

            Map<String, Object> d = data(getAuth("/api/v1/me/stats", me.token()));

            assertThat(d).containsOnlyKeys("successRate", "totalSuccessCount", "streak", "completedCount");
            assertThat((Map<String, Object>) d.get("streak")).containsOnlyKeys("current", "best");
        }

        @Test
        @DisplayName("period 를 보내도 무시한다 — 구 WEEKLY/MONTHLY/YEARLY 는 폐기됐다")
        void period_param_is_gone() throws Exception {
            Member me = member("stats-period");
            MvcResult res = getAuth("/api/v1/me/stats?period=WEEKLY", me.token());
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat(data(res)).doesNotContainKey("period");
        }

        @Test
        @DisplayName("전체 성공률 = 성공 ÷ (성공+실패), 총 성공 인증 수는 성공 건수")
        void success_rate() throws Exception {
            Member me = member("stats-rate");
            UUID ch = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            for (int d = 1; d <= 3; d++) insertOutcome(me.id(), ch, d, "SUCCESS");
            insertOutcome(me.id(), ch, 4, "FAILED");

            Map<String, Object> d = data(getAuth("/api/v1/me/stats", me.token()));

            assertThat(((Number) d.get("successRate")).doubleValue()).isEqualTo(0.75);
            assertThat(d).containsEntry("totalSuccessCount", 3);
        }

        @Test
        @DisplayName("스트릭 — 그날 예정 판정을 전부 성공하면 유지, 하나라도 실패하면 리셋")
        void streak_resets_on_any_failure() throws Exception {
            Member me = member("stats-streak");
            UUID a = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            UUID b = insertChallenge(me.id(), "READING", "ACTIVE", "GROUP");
            // D-1·D-2 는 두 방 모두 성공 → 이어짐. D-3 은 한 방이 실패 → 여기서 끊긴다.
            for (int d = 1; d <= 2; d++) { insertOutcome(me.id(), a, d, "SUCCESS"); insertOutcome(me.id(), b, d, "SUCCESS"); }
            insertOutcome(me.id(), a, 3, "SUCCESS");
            insertOutcome(me.id(), b, 3, "FAILED");
            for (int d = 4; d <= 8; d++) { insertOutcome(me.id(), a, d, "SUCCESS"); insertOutcome(me.id(), b, d, "SUCCESS"); }

            Map<String, Object> streak = (Map<String, Object>) data(getAuth("/api/v1/me/stats", me.token())).get("streak");

            assertThat(streak).containsEntry("current", 2).containsEntry("best", 5);
        }

        @Test
        @DisplayName("판정이 없는 날은 스트릭을 끊지 않는다")
        void streak_survives_days_without_judgement() throws Exception {
            Member me = member("stats-gap");
            UUID ch = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            insertOutcome(me.id(), ch, 1, "SUCCESS");
            // D-2 는 판정 자체가 없다(주 3회 루틴의 쉬는 날).
            insertOutcome(me.id(), ch, 3, "SUCCESS");

            Map<String, Object> streak = (Map<String, Object>) data(getAuth("/api/v1/me/stats", me.token())).get("streak");

            assertThat(streak).containsEntry("current", 2);
        }

        @Test
        @DisplayName("판정 이력이 없으면 성공률 0, 스트릭 0 — 빈 상태에서도 계약을 지킨다")
        void empty_state() throws Exception {
            Member me = member("stats-empty");
            Map<String, Object> d = data(getAuth("/api/v1/me/stats", me.token()));
            assertThat(((Number) d.get("successRate")).doubleValue()).isEqualTo(0.0);
            assertThat(d).containsEntry("totalSuccessCount", 0).containsEntry("completedCount", 0);
        }
    }

    // ================================================================
    @Nested
    @DisplayName("GET /users/me/appeals — 이의 현황, 횟수 한도가 없다")
    class Appeals {

        @Test
        @DisplayName("신청 이력을 최신순으로 내리고 전건이 ACCEPTED 다")
        void history_is_all_accepted() throws Exception {
            Member me = member("appeal-list");
            UUID ch = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            UUID older = insertDaily(ch, me.id(), 9, "FAILED", false);
            UUID newer = insertDaily(ch, me.id(), 2, "FAILED", false);
            insertAppeal(me.id(), ch, older, "지하철에서 GPS 가 끊겨 체류가 기록되지 않았어요", 9);
            insertAppeal(me.id(), ch, newer, "네트워크 지연으로 전송이 늦었어요 확인 부탁드립니다", 2);

            Map<String, Object> d = data(getAuth("/api/v1/users/me/appeals", me.token()));
            List<Map<String, Object>> history = (List<Map<String, Object>>) d.get("history");

            assertThat(history).hasSize(2);
            assertThat(history.getFirst()).containsOnlyKeys(
                    "appealId", "date", "challengeId", "routineTitle", "reason", "track", "result");
            assertThat(history.getFirst().get("reason")).asString().startsWith("네트워크 지연");
            assertThat(history).allSatisfy(h -> assertThat(h).containsEntry("result", "ACCEPTED"));
            assertThat(history.getFirst()).containsEntry("challengeId", ch.toString());
        }

        @Test
        @DisplayName("잔여 구제권 개념이 없다 — credits·resetAt·creditUsed 를 내리지 않는다")
        void no_credits() throws Exception {
            Member me = member("appeal-nocredit");
            Map<String, Object> d = data(getAuth("/api/v1/users/me/appeals", me.token()));
            assertThat(d).containsOnlyKeys("history");
        }

        @Test
        @DisplayName("남의 이의는 보이지 않는다")
        void only_mine() throws Exception {
            Member me = member("appeal-mine");
            Member other = member("appeal-other");
            UUID ch = insertChallenge(other.id(), "EXERCISE", "ACTIVE", "GROUP");
            UUID daily = insertDaily(ch, other.id(), 3, "FAILED", false);
            insertAppeal(other.id(), ch, daily, "제 이의입니다 열 자가 넘도록 적습니다", 3);

            assertThat((List<?>) data(getAuth("/api/v1/users/me/appeals", me.token())).get("history")).isEmpty();
        }
    }

    // ================================================================
    @Nested
    @DisplayName("PATCH /users/me/profile — 닉네임·사진 통합 1개월 잠금")
    class ProfileEdit {

        private Map<String, Object> body(String nickname, List<String> categories, Boolean removeImage) {
            Map<String, Object> b = new LinkedHashMap<>();
            if (nickname != null) b.put("nickname", nickname);
            if (categories != null) b.put("interestCategories", categories);
            if (removeImage != null) b.put("removeProfileImage", removeImage);
            return b;
        }

        @Test
        @DisplayName("구 경로 PATCH /api/v1/profile 은 사라졌다")
        void old_path_is_gone() throws Exception {
            Member me = member("profile-oldpath");
            // 같은 경로에 조회(GET)는 남아 있어 패턴 자체는 매칭된다 → 없는 건 메서드라 405 다.
            assertThat(patchJsonAuth("/api/v1/profile", me.token(), body("새닉네임", null, null))
                    .getResponse().getStatus()).isEqualTo(405);
        }

        @Test
        @DisplayName("닉네임과 관심 분야를 한 번에 저장하고 잠금 해제 시각을 함께 내린다")
        void save_nickname_and_interests() throws Exception {
            Member me = member("profile-save");

            Map<String, Object> d = data(patchJsonAuth("/api/v1/users/me/profile", me.token(),
                    body("새벽러너", List.of("EXERCISE", "READING"), null)));

            assertThat(d).containsOnlyKeys("nickname", "nicknameStatus", "interestCategories", "profileLockedUntil");
            assertThat(d).containsEntry("nickname", "새벽러너")
                    .containsEntry("nicknameStatus", "PENDING");   // 재심사가 시작된다
            assertThat((List<String>) d.get("interestCategories")).containsExactly("EXERCISE", "READING");
            assertThat(d.get("profileLockedUntil")).isNotNull();
        }

        @Test
        @DisplayName("잠금 중 닉네임 재변경은 409 PROFILE_CHANGE_LOCKED")
        void locked_after_change() throws Exception {
            Member me = member("profile-lock");
            patchJsonAuth("/api/v1/users/me/profile", me.token(), body("첫번째닉", null, null));
            // 같은 저장 세션(10분)으로 묶이지 않도록 잠금 시작을 과거로 밀어둔다.
            jdbc().update("UPDATE users SET profile_changed_at = DATE_SUB(NOW(3), INTERVAL 1 DAY) WHERE id = ?",
                    bytes(me.id()));

            expectError(patchJsonAuth("/api/v1/users/me/profile", me.token(), body("두번째닉", null, null)),
                    409, "PROFILE_CHANGE_LOCKED");
        }

        @Test
        @DisplayName("사진 등록 직후 10분 안의 닉네임 변경은 같은 저장 세션이라 잠기지 않는다")
        void same_save_session_within_ten_minutes() throws Exception {
            Member me = member("profile-session");
            patchJsonAuth("/api/v1/users/me/profile", me.token(), body("첫번째닉", null, null));

            // 잠금 시작이 방금이므로 아직 같은 저장 세션이다.
            assertThat(patchJsonAuth("/api/v1/users/me/profile", me.token(), body("두번째닉", null, null))
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("관심 분야는 잠금 예외 — 잠긴 상태에서도 자유롭게 바꾼다")
        void interests_bypass_lock() throws Exception {
            Member me = member("profile-interest");
            patchJsonAuth("/api/v1/users/me/profile", me.token(), body("첫번째닉", null, null));
            jdbc().update("UPDATE users SET profile_changed_at = DATE_SUB(NOW(3), INTERVAL 1 DAY) WHERE id = ?",
                    bytes(me.id()));

            MvcResult res = patchJsonAuth("/api/v1/users/me/profile", me.token(),
                    body(null, List.of("STUDY"), null));

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((List<String>) data(res).get("interestCategories")).containsExactly("STUDY");
        }

        @Test
        @DisplayName("관심 분야 7개는 400 INTEREST_LIMIT_EXCEEDED")
        void interest_limit() throws Exception {
            Member me = member("profile-limit");
            expectError(patchJsonAuth("/api/v1/users/me/profile", me.token(), body(null,
                            List.of("EXERCISE", "READING", "STUDY", "WAKE_SLEEP", "MEAL", "SAVING", "HOBBY"), null)),
                    400, "INTEREST_LIMIT_EXCEEDED");
        }

        @Test
        @DisplayName("모더레이션 거부에 따른 재제출은 잠금에서 제외된다")
        void rejection_fix_bypasses_lock() throws Exception {
            Member me = member("profile-rejected");
            patchJsonAuth("/api/v1/users/me/profile", me.token(), body("첫번째닉", null, null));
            jdbc().update("UPDATE users SET profile_changed_at = DATE_SUB(NOW(3), INTERVAL 1 DAY), " +
                    "nickname_status = 'REJECTED' WHERE id = ?", bytes(me.id()));

            assertThat(patchJsonAuth("/api/v1/users/me/profile", me.token(), body("고친닉네임", null, null))
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("MODERATION_LOCKED 는 폐기됐다 — 거부 횟수만으로 수정을 제한하지 않는다")
        void no_moderation_lock() throws Exception {
            Member me = member("profile-nomodlock");
            jdbc().update("UPDATE users SET nickname_status = 'REJECTED' WHERE id = ?", bytes(me.id()));
            for (int i = 0; i < 4; i++) {
                MvcResult res = patchJsonAuth("/api/v1/users/me/profile", me.token(),
                        body("재제출" + i + "번", null, null));
                assertThat(res.getResponse().getStatus()).isEqualTo(200);
                jdbc().update("UPDATE users SET nickname_status = 'REJECTED' WHERE id = ?", bytes(me.id()));
            }
        }

        @Test
        @DisplayName("removeProfileImage=true 면 기본 프로필로 되돌린다")
        void remove_image() throws Exception {
            Member me = member("profile-rmimg");
            jdbc().update("UPDATE users SET profile_image_url = 'https://cdn/x.png', " +
                    "profile_image_status = 'APPROVED' WHERE id = ?", bytes(me.id()));

            patchJsonAuth("/api/v1/users/me/profile", me.token(), body(null, null, true));

            String url = jdbc().queryForObject("SELECT profile_image_url FROM users WHERE id = ?",
                    String.class, bytes(me.id()));
            assertThat(url).isNull();
        }

        @Test
        @DisplayName("생일·성별은 수정 대상이 아니라 보내도 반영되지 않는다")
        void birthdate_and_gender_are_not_editable() throws Exception {
            Member me = member("profile-immutable");
            String before = jdbc().queryForObject(
                    "SELECT gender FROM user_information WHERE user_id = ?", String.class, bytes(me.id()));

            Map<String, Object> b = body("바꾼닉네임", null, null);
            b.put("gender", "NON_BINARY");
            b.put("birthDate", "1990-01-01");
            patchJsonAuth("/api/v1/users/me/profile", me.token(), b);

            assertThat(jdbc().queryForObject(
                    "SELECT gender FROM user_information WHERE user_id = ?", String.class, bytes(me.id())))
                    .isEqualTo(before);
        }
    }

    // ================================================================
    @Nested
    @DisplayName("GET /me/calendar/{date} — 일자 상세에 이의 가능 여부를 함께 싣는다")
    class CalendarDay {

        private String daysAgo(int n) {
            return jdbc().queryForObject(
                    "SELECT DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY)",
                    String.class, n);
        }

        @Test
        @DisplayName("실패 건에는 appeal 이 붙고 성공 건에는 null 이다")
        void appeal_only_on_failed() throws Exception {
            Member me = member("cal-appeal");
            UUID failed = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            UUID done = insertChallenge(me.id(), "READING", "ACTIVE", "GROUP");
            UUID failedId = insertDaily(failed, me.id(), 1, "FAILED", true);
            insertDaily(done, me.id(), 1, "SUCCESS", false);
            insertOutcome(me.id(), failed, 1, "FAILED");
            insertOutcome(me.id(), done, 1, "SUCCESS");

            Map<String, Object> d = data(getAuth("/api/v1/me/calendar/" + daysAgo(1), me.token()));
            List<Map<String, Object>> items = (List<Map<String, Object>>) d.get("items");

            Map<String, Object> failedItem = items.stream()
                    .filter(i -> failed.toString().equals(i.get("challengeId"))).findFirst().orElseThrow();
            Map<String, Object> doneItem = items.stream()
                    .filter(i -> done.toString().equals(i.get("challengeId"))).findFirst().orElseThrow();

            assertThat(failedItem).containsEntry("verificationId", failedId.toString());
            Map<String, Object> appeal = (Map<String, Object>) failedItem.get("appeal");
            assertThat(appeal).containsOnlyKeys("eligible", "ineligibleReason", "eligibleUntil");
            assertThat(appeal).containsEntry("eligible", true).containsEntry("ineligibleReason", null);
            assertThat(doneItem.get("appeal")).isNull();
        }

        @Test
        @DisplayName("기한이 지난 실패 건은 eligible=false · WINDOW_CLOSED")
        void window_closed() throws Exception {
            Member me = member("cal-closed");
            UUID ch = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            insertDaily(ch, me.id(), 5, "FAILED", false);
            insertOutcome(me.id(), ch, 5, "FAILED");

            List<Map<String, Object>> items = (List<Map<String, Object>>)
                    data(getAuth("/api/v1/me/calendar/" + daysAgo(5), me.token())).get("items");
            Map<String, Object> appeal = (Map<String, Object>) items.getFirst().get("appeal");

            assertThat(appeal).containsEntry("eligible", false)
                    .containsEntry("ineligibleReason", "WINDOW_CLOSED");
            assertThat(appeal.get("eligibleUntil")).isNotNull();
        }

        @Test
        @DisplayName("잔여 횟수 개념이 없어 remainingThisMonth·LIMIT_EXCEEDED 를 내리지 않는다")
        void no_remaining_count() throws Exception {
            Member me = member("cal-nolimit");
            UUID ch = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            insertDaily(ch, me.id(), 1, "FAILED", true);
            insertOutcome(me.id(), ch, 1, "FAILED");

            List<Map<String, Object>> items = (List<Map<String, Object>>)
                    data(getAuth("/api/v1/me/calendar/" + daysAgo(1), me.token())).get("items");

            assertThat((Map<String, Object>) items.getFirst().get("appeal"))
                    .doesNotContainKeys("remainingThisMonth", "creditUsed");
        }

        @Test
        @DisplayName("이미 이의를 낸 건은 다시 신청할 수 없다")
        void already_appealed() throws Exception {
            Member me = member("cal-already");
            UUID ch = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            UUID daily = insertDaily(ch, me.id(), 1, "FAILED", true);
            insertAppeal(me.id(), ch, daily, "이미 신청한 이의입니다 열 자 이상", 1);
            insertOutcome(me.id(), ch, 1, "FAILED");

            List<Map<String, Object>> items = (List<Map<String, Object>>)
                    data(getAuth("/api/v1/me/calendar/" + daysAgo(1), me.token())).get("items");

            assertThat((Map<String, Object>) items.getFirst().get("appeal"))
                    .containsEntry("eligible", false)
                    .containsEntry("ineligibleReason", "ALREADY_APPEALED");
        }
    }

    // ================================================================
    @Nested
    @DisplayName("GET /users/{userId}/profile — 공개 범위를 서버가 강제한다")
    class PublicProfile {

        @Test
        @DisplayName("점수·통계·캘린더는 값이 가려지는 게 아니라 필드 자체가 없다")
        void narrow_shape() throws Exception {
            Member me = member("pub-viewer");
            Member target = member("pub-target");
            setScore(target.id(), 370, "GOLD", "GOLD");

            Map<String, Object> d = data(getAuth("/api/v1/users/" + target.id() + "/profile", me.token()));

            assertThat(d).containsOnlyKeys("userId", "nickname", "profileImageUrl", "displayTier",
                    "completedChallengeCount", "withdrawn", "blocked");
            assertThat(d).containsEntry("displayTier", "GOLD");
        }

        @Test
        @DisplayName("없는 사용자는 404 USER_NOT_FOUND")
        void not_found() throws Exception {
            Member me = member("pub-404");
            expectError(getAuth("/api/v1/users/" + UUID.randomUUID() + "/profile", me.token()),
                    404, "USER_NOT_FOUND");
        }
    }

    // ================================================================
    @Nested
    @DisplayName("GET /me/home — 티어 3종 + 진행/완주/이탈 카운트")
    class Home {

        @Test
        @DisplayName("매너 온도와 부정행위 누적 카운트는 응답에서 사라졌다")
        void tier_replaces_temperature() throws Exception {
            Member me = member("home-tier");
            setScore(me.id(), 370, "GOLD", "GOLD");

            Map<String, Object> d = data(getAuth("/api/v1/me/home", me.token()));

            assertThat(d).containsEntry("tier", "GOLD").containsEntry("score", 370)
                    .containsEntry("displayTier", "GOLD")
                    .containsEntry("accountStatus", "ACTIVE");
            assertThat(d).doesNotContainKeys("mannerTemperature", "cheatCount");
            assertThat((Map<String, Object>) d.get("counts"))
                    .containsOnlyKeys("inProgress", "completed", "left");
        }

        @Test
        @DisplayName("정지된 계정은 accountStatus=LOCKED 와 잠금 사유·해제일을 함께 내린다")
        void locked_account() throws Exception {
            Member me = member("home-locked");
            lock(me.id());

            Map<String, Object> d = data(getAuth("/api/v1/me/home", me.token()));

            assertThat(d).containsEntry("accountStatus", "LOCKED");
            assertThat((Map<String, Object>) d.get("lockInfo")).containsKeys("reason", "unlockAt");
        }
    }
}
