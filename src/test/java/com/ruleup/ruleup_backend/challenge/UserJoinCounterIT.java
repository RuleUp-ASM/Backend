package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.counter.UserJoinCounterReconciliationService;
import com.ruleup.ruleup_backend.challenge.counter.UserJoinCounterService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 동시 참여 슬롯 카운터 계약 테스트.
 *
 * <p>이 값이 실제보다 크면 사용자는 아무 방에도 못 들어가고 스스로 풀 방법도 없다(종료된 방은 탈퇴 불가).
 * 그래서 "진실이 무엇인가"와 "어긋나면 저절로 낫는가" 두 가지를 고정한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserJoinCounterIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserJoinCounterService joinCounterService;
    @Autowired UserJoinCounterReconciliationService reconciliationService;
    @Autowired ChallengeCompletionService completionService;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private UUID room(UUID ownerId, String status) {
        UUID id = insertChallenge(ownerId, "EXERCISE", status, "GROUP");
        insertActiveMembership(id, ownerId, "OWNER");
        return id;
    }

    // =====================================================================
    @Nested
    @DisplayName("슬롯의 진실 — 무엇을 세고 무엇을 안 세는가")
    class Truth {

        @Test
        @DisplayName("시작 전(UPCOMING) 방은 센다 — 시작 전에도 가입이 열려 있어 실제로 자리를 차지한다")
        void countsUpcoming() throws Exception {
            Member user = member(uniq("cnt-upcoming"));
            room(user.id(), "UPCOMING");

            assertThat(joinCounterService.countActiveSlots(user.id())).isEqualTo(1);
        }

        @Test
        @DisplayName("종료(COMPLETED) 방은 세지 않는다 — 끝난 방이 자리를 잡고 있으면 안 된다")
        void ignoresCompleted() throws Exception {
            Member user = member(uniq("cnt-done"));
            room(user.id(), "COMPLETED");

            assertThat(joinCounterService.countActiveSlots(user.id())).isZero();
        }

        @Test
        @DisplayName("나갔거나 강퇴된 멤버십은 세지 않는다")
        void ignoresLeftAndRemoved() throws Exception {
            Member user = member(uniq("cnt-left"));
            UUID left = room(user.id(), "ACTIVE");
            UUID removed = room(user.id(), "ACTIVE");
            jdbcTemplate.update("UPDATE challenge_members SET status = 'LEFT' WHERE challenge_id = ?",
                    (Object) bytes(left));
            jdbcTemplate.update("UPDATE challenge_members SET status = 'REMOVED' WHERE challenge_id = ?",
                    (Object) bytes(removed));

            assertThat(joinCounterService.countActiveSlots(user.id())).isZero();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("보정 배치")
    class Reconciliation {

        @Test
        @DisplayName("저장값이 원천과 어긋나면 되돌리고, 이미 맞으면 아무것도 하지 않는다")
        void fixesDriftThenNoOp() throws Exception {
            Member user = member(uniq("recon-drift"));
            room(user.id(), "ACTIVE");
            setCounter(user.id(), 3);        // 실제 1개인데 3으로 어긋난 상태

            assertThat(reconciliationService.runOnce()).isPositive();
            assertThat(counterOf(user.id())).isEqualTo(1);

            // 어긋난 곳이 없으면 이 사용자 때문에 고쳐지는 건 없어야 한다
            int before = counterOf(user.id());
            reconciliationService.runOnce();
            assertThat(counterOf(user.id())).isEqualTo(before);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("공개 상세는 회수된 슬롯을 바로 반영한다")
    class DetailPreview {

        @Test
        @DisplayName("방 3개가 모두 끝나면 상세의 joinBlockReason 이 FREE_LIMIT 에서 풀린다")
        void detailStopsShowingFreeLimit() throws Exception {
            Member user = member(uniq("detail-slot"));
            Member owner = member(uniq("detail-slot-owner"));
            var rooms = occupySlots(user.id(), 3);

            UUID target = room(owner.id(), "ACTIVE");
            jdbcTemplate.update("UPDATE challenges SET visibility = 'PUBLIC' WHERE id = ?", (Object) bytes(target));
            MvcResult blocked = getAuth("/api/v1/challenges/" + target, user.token());
            assertThat((String) read(blocked, "$.data.joinBlockReason")).isEqualTo("FREE_LIMIT");

            for (UUID id : rooms) {
                jdbcTemplate.update("UPDATE challenges SET start_date = DATE_SUB(CURDATE(), INTERVAL 10 DAY), " +
                        "end_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY) WHERE id = ?", (Object) bytes(id));
            }
            completionService.completeEndedChallenges();

            MvcResult freed = getAuth("/api/v1/challenges/" + target, user.token());
            assertThat((String) read(freed, "$.data.joinBlockReason")).isNotEqualTo("FREE_LIMIT");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("회원 탈퇴")
    class Withdrawal {

        @Test
        @DisplayName("탈퇴하면 참여 중인 방에서 전부 나가고 슬롯이 0이 된다 — 방장이던 방은 봇방장으로")
        void withdrawLeavesAllRooms() throws Exception {
            Member user = member(uniq("wd-user"));
            List<UUID> rooms = occupySlots(user.id(), 2);

            MvcResult res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/users/me")
                            .header("Authorization", "Bearer " + user.token())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(OM.writeValueAsString(Map.of("confirmPhrase", "탈퇴할게요"))))
                    .andReturn();
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            for (UUID id : rooms) {
                String status = jdbcTemplate.queryForObject(
                        "SELECT status FROM challenge_members WHERE challenge_id = ? AND user_id = ?",
                        String.class, bytes(id), bytes(user.id()));
                assertThat(status).isEqualTo("LEFT");
                String ownerType = jdbcTemplate.queryForObject(
                        "SELECT owner_type FROM challenges WHERE id = ?", String.class, bytes(id));
                assertThat(ownerType).isEqualTo("BOT");
            }
            assertThat(counterOf(user.id())).isZero();
        }
    }
}
