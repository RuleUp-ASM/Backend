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
    @DisplayName("방 운영 데이터가 남아 있어도 자동 삭제가 완주한다")
    class RoomDataCascade {

        @Test
        @DisplayName("초대가 달린 만료 방도 하드 삭제되고 이력 스냅샷은 남는다")
        void deleteWithRoomData() throws Exception {
            Member owner = member(uniq("lc-roomdata"));
            UUID id = insertChallenge(owner.id(), "EXERCISE", "COMPLETED", "GROUP");
            insertActiveMembership(id, owner.id(), "OWNER");

            // 방 운영 기능이 만든 자식 행 — challenges 를 FK 로 참조한다.
            // 공지·댓글은 Phase 2 이관과 함께 테이블째 사라져(V16) 여기 남는 자식은 초대뿐이다.
            jdbcTemplate.update("INSERT INTO challenge_invitations " +
                            "(id, challenge_id, inviter_id, token_hash, expires_at) " +
                            "VALUES (?, ?, ?, ?, DATE_ADD(NOW(6), INTERVAL 7 DAY))",
                    bytes(UUID.randomUUID()), bytes(id), bytes(owner.id()), new byte[32]);

            autoDeleteService.runOnce();

            assertThat(challengeCount(id)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_invitations WHERE challenge_id = ?", Integer.class, bytes(id))).isZero();
            // 이력 스냅샷은 남아야 완료 목록·최종 랭킹을 계속 볼 수 있다
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_history WHERE challenge_id = ?", Integer.class, bytes(id))).isEqualTo(1);
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

    // =====================================================================
    @Nested
    @DisplayName("동시 참여 슬롯 회수")
    class JoinSlotRelease {

        /** 방 하나를 어제 끝난 것으로 만든다(종료 배치가 집도록). */
        private void endYesterday(UUID challengeId) {
            jdbcTemplate.update("UPDATE challenges SET start_date = DATE_SUB(CURDATE(), INTERVAL 10 DAY), " +
                    "end_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY) WHERE id = ?", (Object) bytes(challengeId));
        }

        @Test
        @DisplayName("종료 배치가 끝난 방의 슬롯을 돌려준다 — 막혀 있던 가입이 통과한다")
        void completionReleasesSlot() throws Exception {
            Member user = member(uniq("slot-done"));
            Member owner = member(uniq("slot-done-owner"));
            var rooms = occupySlots(user.id(), 3);
            assertThat(counterOf(user.id())).isEqualTo(3);

            // 한 방이 어제로 끝났다 → 슬롯 하나가 풀려야 한다
            endYesterday(rooms.get(0));
            completionService.completeEndedChallenges();

            assertThat(counterOf(user.id())).isEqualTo(2);

            UUID target = insertChallenge(owner.id(), "STUDY", "ACTIVE", "GROUP");
            insertActiveMembership(target, owner.id(), "OWNER");
            jdbcTemplate.update("UPDATE challenges SET visibility = 'PUBLIC' WHERE id = ?", (Object) bytes(target));
            MvcResult joined = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/challenges/" + target + "/members")
                    .header("Authorization", "Bearer " + user.token())).andReturn();
            assertThat(joined.getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("멤버 행은 ACTIVE 로 남는다 — 카운터에서만 뺀다(완주 기록·최종 랭킹의 근거)")
        void keepsMembershipRow() throws Exception {
            Member user = member(uniq("slot-keep"));
            var rooms = occupySlots(user.id(), 1);
            endYesterday(rooms.get(0));

            completionService.completeEndedChallenges();

            String memberStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM challenge_members WHERE challenge_id = ? AND user_id = ?",
                    String.class, bytes(rooms.get(0)), bytes(user.id()));
            assertThat(memberStatus).isEqualTo("ACTIVE");
            assertThat(counterOf(user.id())).isZero();
        }

        @Test
        @DisplayName("종료 배치를 두 번 돌려도 카운터가 더 내려가지 않는다(재계산은 멱등)")
        void completionIsIdempotent() throws Exception {
            Member user = member(uniq("slot-idem"));
            var rooms = occupySlots(user.id(), 2);
            endYesterday(rooms.get(0));

            completionService.completeEndedChallenges();
            completionService.completeEndedChallenges();

            assertThat(counterOf(user.id())).isEqualTo(1);
        }

        @Test
        @DisplayName("자동 삭제도 슬롯을 돌려주며, 두 번 돌아도 음수가 되지 않는다")
        void autoDeleteReleasesSlotOnce() throws Exception {
            Member user = member(uniq("slot-del"));
            var rooms = occupySlots(user.id(), 1);
            jdbcTemplate.update("UPDATE challenges SET status = 'COMPLETED' WHERE id = ?",
                    (Object) bytes(rooms.get(0)));

            autoDeleteService.runOnce();
            autoDeleteService.runOnce();

            assertThat(counterOf(user.id())).isZero();
        }
    }
}
