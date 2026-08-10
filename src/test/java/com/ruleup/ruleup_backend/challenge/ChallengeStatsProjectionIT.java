package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsProjectionService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsReconciliationService;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * `challenge_stats` Projection 계약 테스트 — 탐색 정책 §4.4 · 백엔드 테크스펙 §4-4·§19-1.
 *
 * <p>완주율·유지율은 표본이 적을 때 왜곡되기 쉬운 값이라(2명 중 2명 성공 = 100%),
 * 표본 조건을 만족할 때만 값을 낸다. 값이 없으면 화면에 표시하지 않고 해당 지표 정렬에서도 빠진다.
 * 경계값을 한 칸씩 넘나들며 고정한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeStatsProjectionIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ChallengeStatsProjectionService projectionService;
    @Autowired ChallengeStatsReconciliationService reconciliationService;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    // ===== 헬퍼 =====

    /** 진행 중(ACTIVE) 공개 그룹 방 1개. 방장 멤버십은 넣지 않아 통계 표본을 테스트가 직접 만든다. */
    private UUID activeChallenge(UUID ownerId) {
        UUID id = insertChallenge(ownerId, "EXERCISE", "ACTIVE", "GROUP");
        jdbcTemplate.update("UPDATE challenges SET visibility = 'PUBLIC', participant_count = 0 WHERE id = ?",
                (Object) bytes(id));
        return id;
    }

    /**
     * 확정 판정 이력을 가진 멤버 1명을 넣는다.
     *
     * @param targetDays 전체 대상일 — 확정 실패(남은 날 전부 성공해도 80% 불가) 판정에 쓴다
     */
    private void insertMemberWithProgress(UUID challengeId, int successDays, int failDays, int targetDays)
            throws Exception {
        // users 에 oauth 체크 제약이 있어 가입 플로우로 실제 회원을 만든다.
        UUID userId = member(uniq("stat")).id();
        jdbcTemplate.update("INSERT INTO challenge_members " +
                        "(id, challenge_id, user_id, role, status, success_days, fail_days, target_days) " +
                        "VALUES (?, ?, ?, 'MEMBER', 'ACTIVE', ?, ?, ?)",
                bytes(UUID.randomUUID()), bytes(challengeId), bytes(userId), successDays, failDays, targetDays);
        jdbcTemplate.update("UPDATE challenges SET participant_count = participant_count + 1 WHERE id = ?",
                (Object) bytes(challengeId));
    }

    private BigDecimal completionRate(UUID challengeId) {
        return jdbcTemplate.queryForObject(
                "SELECT completion_rate FROM challenge_stats WHERE challenge_id = ?",
                BigDecimal.class, bytes(challengeId));
    }

    private BigDecimal retentionRate(UUID challengeId) {
        return jdbcTemplate.queryForObject(
                "SELECT retention_rate FROM challenge_stats WHERE challenge_id = ?",
                BigDecimal.class, bytes(challengeId));
    }

    // =====================================================================
    @Nested
    @DisplayName("완주율 표본 경계 — 확정 판정 10회 이상 멤버가 5명 이상일 때만")
    class CompletionRateSample {

        @Test
        @DisplayName("자격 멤버 4명이면 NULL, 5명이 되면 값이 생긴다")
        void qualifiedMemberBoundary() throws Exception {
            UUID owner = member(uniq("cr-owner")).id();
            UUID challengeId = activeChallenge(owner);
            for (int i = 0; i < 4; i++) insertMemberWithProgress(challengeId, 10, 0, 12);

            projectionService.refresh(challengeId);
            assertThat(completionRate(challengeId)).isNull();

            insertMemberWithProgress(challengeId, 10, 0, 12);
            projectionService.refresh(challengeId);
            assertThat(completionRate(challengeId)).isNotNull();
        }

        @Test
        @DisplayName("확정 판정 9회는 자격 미달, 10회부터 표본에 든다")
        void progressCountBoundary() throws Exception {
            UUID owner = member(uniq("cr-progress")).id();
            UUID challengeId = activeChallenge(owner);
            for (int i = 0; i < 5; i++) insertMemberWithProgress(challengeId, 5, 4, 12);   // 9회

            projectionService.refresh(challengeId);
            assertThat(completionRate(challengeId)).isNull();

            for (int i = 0; i < 5; i++) insertMemberWithProgress(challengeId, 6, 4, 12);   // 10회
            projectionService.refresh(challengeId);
            assertThat(completionRate(challengeId)).isNotNull();
        }

        @Test
        @DisplayName("성공률 79%는 완주로 세지 않고 80%부터 센다")
        void successRateBoundary() throws Exception {
            UUID owner = member(uniq("cr-rate")).id();
            UUID challengeId = activeChallenge(owner);
            // 5명 전부 79/100 → 완주 0명
            for (int i = 0; i < 5; i++) insertMemberWithProgress(challengeId, 79, 21, 100);
            projectionService.refresh(challengeId);
            assertThat(completionRate(challengeId)).isEqualByComparingTo("0.0000");

            // 5명 더, 전부 정확히 80/100 → 완주 5명 / 자격 10명
            for (int i = 0; i < 5; i++) insertMemberWithProgress(challengeId, 80, 20, 100);
            projectionService.refresh(challengeId);
            assertThat(completionRate(challengeId)).isEqualByComparingTo("0.5000");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("유지율 표본 경계 — 방 누적 확정 판정 30회 이상일 때만")
    class RetentionRateSample {

        @Test
        @DisplayName("누적 29회면 NULL, 30회가 되면 값이 생긴다")
        void totalProgressBoundary() throws Exception {
            UUID owner = member(uniq("rr-owner")).id();
            UUID challengeId = activeChallenge(owner);
            insertMemberWithProgress(challengeId, 29, 0, 40);

            projectionService.refresh(challengeId);
            assertThat(retentionRate(challengeId)).isNull();

            insertMemberWithProgress(challengeId, 1, 0, 40);   // 누적 30
            projectionService.refresh(challengeId);
            assertThat(retentionRate(challengeId)).isNotNull();
        }

        @Test
        @DisplayName("확정 실패한 멤버는 분자에서 빠진다 — 남은 날을 다 성공해도 80%가 불가능한 사람")
        void confirmedFailureExcluded() throws Exception {
            UUID owner = member(uniq("rr-fail")).id();
            UUID challengeId = activeChallenge(owner);
            // targetDays 20, 실패 5 → 최대 15/20 = 75% < 80% → 확정 실패
            insertMemberWithProgress(challengeId, 15, 5, 20);
            // targetDays 20, 실패 4 → 최대 16/20 = 80% → 아직 확정 실패 아님
            insertMemberWithProgress(challengeId, 16, 4, 20);

            projectionService.refresh(challengeId);
            assertThat(retentionRate(challengeId)).isEqualByComparingTo("0.5000");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("시작 전 방과 갱신 경로")
    class UpcomingAndRefresh {

        @Test
        @DisplayName("UPCOMING 방은 표본이 충분해도 두 지표 모두 NULL이다")
        void upcomingAlwaysNull() throws Exception {
            UUID owner = member(uniq("up-owner")).id();
            UUID challengeId = insertChallenge(owner, "EXERCISE", "UPCOMING", "GROUP");
            jdbcTemplate.update("UPDATE challenges SET visibility = 'PUBLIC', participant_count = 0 WHERE id = ?",
                    (Object) bytes(challengeId));
            for (int i = 0; i < 6; i++) insertMemberWithProgress(challengeId, 40, 0, 50);

            projectionService.refresh(challengeId);
            assertThat(completionRate(challengeId)).isNull();
            assertThat(retentionRate(challengeId)).isNull();
        }

        @Test
        @DisplayName("stats 행이 없는 방도 재계산이 행을 만들어 준다 — 갱신 경로가 자가 치유된다")
        void refreshCreatesMissingRow() throws Exception {
            UUID owner = member(uniq("stats-missing")).id();
            UUID challengeId = activeChallenge(owner);
            jdbcTemplate.update("DELETE FROM challenge_stats WHERE challenge_id = ?", (Object) bytes(challengeId));

            projectionService.refresh(challengeId);

            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_stats WHERE challenge_id = ?",
                    Integer.class, bytes(challengeId));
            assertThat(rows).isEqualTo(1);
        }

        @Test
        @DisplayName("탈퇴한 멤버는 그룹 통계에서 빠진다 — 재계산이 원천을 다시 읽는다")
        void leftMemberExcluded() throws Exception {
            UUID owner = member(uniq("left-owner")).id();
            UUID challengeId = activeChallenge(owner);
            for (int i = 0; i < 5; i++) insertMemberWithProgress(challengeId, 10, 0, 12);
            projectionService.refresh(challengeId);
            assertThat(completionRate(challengeId)).isNotNull();

            // 한 명이 나가면 자격 멤버가 4명 → 표본 미달로 다시 NULL
            jdbcTemplate.update("UPDATE challenge_members SET status = 'LEFT' WHERE challenge_id = ? LIMIT 1",
                    (Object) bytes(challengeId));
            jdbcTemplate.update("UPDATE challenges SET participant_count = participant_count - 1 WHERE id = ?",
                    (Object) bytes(challengeId));
            projectionService.refresh(challengeId);
            assertThat(completionRate(challengeId)).isNull();
        }

        @Test
        @DisplayName("이벤트가 유실돼도 일 1회 reconciliation이 원천 기준으로 되돌린다")
        void reconciliationRepairsDrift() throws Exception {
            UUID owner = member(uniq("recon-owner")).id();
            UUID challengeId = activeChallenge(owner);
            for (int i = 0; i < 5; i++) insertMemberWithProgress(challengeId, 10, 0, 12);
            projectionService.refresh(challengeId);

            // 이벤트 유실을 흉내 내 통계만 오염시킨다(원천은 그대로)
            jdbcTemplate.update("UPDATE challenge_stats SET completion_rate = NULL, " +
                    "qualified_member_count = 0 WHERE challenge_id = ?", (Object) bytes(challengeId));

            int repaired = reconciliationService.runOnce();
            assertThat(repaired).isPositive();
            assertThat(completionRate(challengeId)).isNotNull();
        }
    }
}
