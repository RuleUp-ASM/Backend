package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeActivationService;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeAutoDeleteService;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeCleanupService;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeCompletionService;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * 챌린지 라이프사이클 배치 계약 테스트 — 시작 전 → 진행 중 → 자동 삭제 (기능 스펙 6-1 #9·백엔드 4-5).
 *
 *  - 활성화: 시작일 도래 UPCOMING → ACTIVE. 심사 상태와 무관(심사 중 기능 제한 없음 — 구 게이트 폐기).
 *  - 완료: 종료일 경과 ACTIVE → COMPLETED.
 *  - 자동 삭제(만료·유령방): 삭제 직전 이력 스냅샷 3종(challenge_history·member·final_ranking) 적재 →
 *    잔디(방 데이터) 삭제·방 하드 삭제. 인증 기록 원본(VerificationDaily)·통계는 보존(소프트 참조).
 *  - 방장 수동 삭제 API 는 폐기 — 삭제는 자동 조건뿐.
 *  - 부속 정리: 만료(24h) 초안·미등록(24h) 업로드 이미지.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeLifecycleBatchIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ChallengeActivationService activationService;
    @Autowired ChallengeCompletionService completionService;
    @Autowired ChallengeAutoDeleteService autoDeleteService;
    @Autowired ChallengeCleanupService cleanupService;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private String status(UUID challengeId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM challenges WHERE id = ?", String.class, bytes(challengeId));
    }

    private int challengeCount(UUID challengeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM challenges WHERE id = ?", Integer.class, bytes(challengeId));
    }

    // =====================================================================
    @Nested
    @DisplayName("활성화·완료 전환")
    class Transitions {

        @Test
        @DisplayName("시작일 도래 UPCOMING → ACTIVE — 심사 중(IN_REVIEW)이어도 활성화(기능 제한 없음)")
        void activateEvenWhenInReview() throws Exception {
            Member owner = member(uniq("lc-act"));
            UUID id = insertChallenge(owner.id(), "EXERCISE", "UPCOMING", "SOLO");
            insertActiveMembership(id, owner.id(), "OWNER");
            jdbcTemplate.update("UPDATE challenges SET start_date = CURDATE(), " +
                    "moderation_title = 'IN_REVIEW' WHERE id = ?", bytes(id));

            activationService.activateDueChallenges();

            assertThat(status(id)).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("종료일 경과 ACTIVE → COMPLETED")
        void completeEnded() throws Exception {
            Member owner = member(uniq("lc-done"));
            UUID id = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "SOLO");
            insertActiveMembership(id, owner.id(), "OWNER");
            jdbcTemplate.update("UPDATE challenges SET end_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY) " +
                    "WHERE id = ?", bytes(id));

            completionService.completeEndedChallenges();

            assertThat(status(id)).isEqualTo("COMPLETED");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("자동 삭제 — 만료·유령방 + 이력 스냅샷 + 원본 보존")
    class AutoDelete {

        @Test
        @DisplayName("기간 만료(COMPLETED) 방: 이력 3종 적재 후 하드 삭제, 인증 원본은 보존")
        void deleteCompletedWithSnapshots() throws Exception {
            Member owner = member(uniq("lc-del"));
            UUID id = insertChallenge(owner.id(), "EXERCISE", "COMPLETED", "SOLO");
            insertActiveMembership(id, owner.id(), "OWNER");

            // 인증 기록 원본(잔디의 근거) — 삭제 후에도 남아야 한다(소프트 참조)
            UUID memberId = UUID.fromString(jdbcTemplate.queryForObject(
                    "SELECT LOWER(CONCAT(SUBSTR(HEX(id),1,8),'-',SUBSTR(HEX(id),9,4),'-',SUBSTR(HEX(id),13,4),'-'," +
                            "SUBSTR(HEX(id),17,4),'-',SUBSTR(HEX(id),21))) FROM challenge_members WHERE challenge_id = ?",
                    String.class, bytes(id)));
            jdbcTemplate.update("INSERT INTO VerificationDaily (id, challengeMemberId, challengeId, userId, targetDate, status) " +
                            "VALUES (?, ?, ?, ?, CURDATE(), 'SUCCESS')",
                    bytes(UUID.randomUUID()), bytes(memberId), bytes(id), bytes(owner.id()));

            autoDeleteService.runOnce();

            // 방·멤버 행은 하드 삭제
            assertThat(challengeCount(id)).isZero();
            Integer members = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_members WHERE challenge_id = ?", Integer.class, bytes(id));
            assertThat(members).isZero();

            // 이력 스냅샷 3종
            Map<String, Object> history = jdbcTemplate.queryForMap(
                    "SELECT title_snapshot, category FROM challenge_history WHERE challenge_id = ?", bytes(id));
            assertThat(history.get("title_snapshot")).isEqualTo("테스트 챌린지");
            assertThat(history.get("category")).isEqualTo("EXERCISE");

            Map<String, Object> memberHistory = jdbcTemplate.queryForMap(
                    "SELECT final_role, left_type FROM challenge_member_history WHERE challenge_id = ? AND user_id = ?",
                    bytes(id), bytes(owner.id()));
            assertThat(memberHistory.get("final_role")).isEqualTo("OWNER");

            Integer ranking = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_final_ranking WHERE challenge_id = ?", Integer.class, bytes(id));
            assertThat(ranking).isGreaterThanOrEqualTo(1);

            // 인증 기록 원본 보존
            Integer verifications = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM VerificationDaily WHERE challengeId = ?", Integer.class, bytes(id));
            assertThat(verifications).isEqualTo(1);
        }

        @Test
        @DisplayName("유령방(ACTIVE 멤버 0명)도 자동 삭제 대상")
        void deleteGhostRoom() throws Exception {
            Member owner = member(uniq("lc-ghost"));
            UUID id = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
            // ACTIVE 멤버십 없음(전원 이탈한 방)

            autoDeleteService.runOnce();

            assertThat(challengeCount(id)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_history WHERE challenge_id = ?",
                    Integer.class, bytes(id))).isEqualTo(1);
        }

        @Test
        @DisplayName("진행 중 + ACTIVE 멤버가 있는 방은 삭제되지 않는다")
        void keepActiveRoom() throws Exception {
            Member owner = member(uniq("lc-keep"));
            UUID id = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "SOLO");
            insertActiveMembership(id, owner.id(), "OWNER");

            autoDeleteService.runOnce();

            assertThat(challengeCount(id)).isEqualTo(1);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("부속 정리 — 만료 초안·미등록 이미지")
    class Cleanup {

        @Test
        @DisplayName("만료(24h 경과) 초안은 삭제, 유효 초안은 유지")
        void cleanupDrafts() throws Exception {
            Member m = member(uniq("lc-draft"));
            jdbcTemplate.update("INSERT INTO challenge_drafts (id, user_id, origin, title, description, payload, expires_at) " +
                            "VALUES (?, ?, 'AI', '만료 초안', '설명', '{}', DATE_SUB(NOW(), INTERVAL 1 HOUR))",
                    bytes(UUID.randomUUID()), bytes(m.id()));
            UUID aliveId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO challenge_drafts (id, user_id, origin, title, description, payload, expires_at) " +
                            "VALUES (?, ?, 'AI', '유효 초안', '설명', '{}', DATE_ADD(NOW(), INTERVAL 10 HOUR))",
                    bytes(aliveId), bytes(m.id()));

            cleanupService.cleanupExpiredDrafts();

            Integer expired = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_drafts WHERE user_id = ? AND title = '만료 초안'",
                    Integer.class, bytes(m.id()));
            Integer alive = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_drafts WHERE id = ?", Integer.class, bytes(aliveId));
            assertThat(expired).isZero();
            assertThat(alive).isEqualTo(1);
        }

        @Test
        @DisplayName("24시간 지나도록 등록되지 않은 업로드 이미지는 정리, 등록분은 유지")
        void cleanupOrphanImages() throws Exception {
            Member m = member(uniq("lc-img"));
            jdbcTemplate.update("INSERT INTO challenge_image_uploads (user_id, image_url, registered_at, created_at) " +
                            "VALUES (?, '/uploads/orphan.png', NULL, DATE_SUB(NOW(), INTERVAL 25 HOUR))", bytes(m.id()));
            jdbcTemplate.update("INSERT INTO challenge_image_uploads (user_id, image_url, registered_at, created_at) " +
                            "VALUES (?, '/uploads/registered.png', NOW(), DATE_SUB(NOW(), INTERVAL 25 HOUR))", bytes(m.id()));
            jdbcTemplate.update("INSERT INTO challenge_image_uploads (user_id, image_url, registered_at, created_at) " +
                            "VALUES (?, '/uploads/fresh.png', NULL, NOW())", bytes(m.id()));

            cleanupService.cleanupOrphanImageUploads();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_image_uploads WHERE image_url = '/uploads/orphan.png'",
                    Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_image_uploads WHERE image_url IN ('/uploads/registered.png','/uploads/fresh.png')",
                    Integer.class)).isEqualTo(2);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("방장 수동 삭제 폐기")
    class ManualDeleteRemoved {

        @Test
        @DisplayName("DELETE /api/v1/challenges/{id} 는 더 이상 존재하지 않는다(자동 삭제로 일원화)")
        void deleteApiRemoved() throws Exception {
            Member owner = member(uniq("lc-noapi"));
            UUID id = insertChallenge(owner.id(), "EXERCISE", "UPCOMING", "SOLO");

            MvcResult res = mvc.perform(delete("/api/v1/challenges/" + id)
                            .header("Authorization", "Bearer " + owner.token()))
                    .andReturn();
            assertThat(res.getResponse().getStatus()).isIn(404, 405);
        }
    }
}
