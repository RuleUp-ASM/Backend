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

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * 가입 게이트 5중 검사 · 탈퇴 · 방장 승계 회귀 계약 테스트.
 *
 * <p>정책 §5·§10·§11 → 통합 테크스펙 5-1(유형 3) → 백엔드 4-3 → 가입·탈퇴 API 명세 순서로 고정한다.
 * 거절은 전부 409 {@code JOIN_BLOCKED} + reason 단일 형식이어야 한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeJoinGateIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    // ===== 헬퍼 =====

    private MvcResult join(String token, UUID challengeId) throws Exception {
        return postJsonAuth("/api/v1/challenges/" + challengeId + "/members", token, Map.of());
    }

    private MvcResult leave(String token, UUID challengeId) throws Exception {
        return mvc.perform(delete("/api/v1/challenges/" + challengeId + "/members/me")
                .header("Authorization", "Bearer " + token)).andReturn();
    }

    /** 방장 1명이 이미 들어 있는 공개 그룹 방(가입 대상). */
    private UUID openGroup(UUID ownerId) {
        UUID challengeId = insertChallenge(ownerId, "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, ownerId, "OWNER");
        jdbcTemplate.update("UPDATE challenges SET visibility = 'PUBLIC' WHERE id = ?", (Object) bytes(challengeId));
        return challengeId;
    }

    private void setCounter(UUID userId, int count) {
        jdbcTemplate.update("INSERT INTO user_challenge_counters (user_id, active_join_count) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE active_join_count = VALUES(active_join_count)", bytes(userId), count);
    }

    private void expectBlocked(MvcResult res, String reason) throws Exception {
        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) read(res, "$.error.code")).isEqualTo("JOIN_BLOCKED");
        assertThat((String) read(res, "$.error.reason")).isEqualTo(reason);
    }

    // =====================================================================
    @Nested
    @DisplayName("가입 게이트")
    class JoinGate {

        @Test
        @DisplayName("통과 → joined + countFromCycle + requiredPermissions + personalSetupRequired")
        void joinContract() throws Exception {
            Member owner = member(uniq("gate-owner"));
            Member joiner = member(uniq("gate-joiner"));
            UUID challengeId = openGroup(owner.id());

            MvcResult res = join(joiner.token(), challengeId);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.joined")).isTrue();
            assertThat((String) read(res, "$.data.countFromCycle")).isNotBlank();
            // 수동 인증 방 → 필요 권한 없음, 개인 설정 불필요
            assertThat((Integer) read(res, "$.data.requiredPermissions.length()")).isZero();
            assertThat((Boolean) read(res, "$.data.personalSetupRequired")).isFalse();

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT active_join_count FROM user_challenge_counters WHERE user_id = ?",
                    Integer.class, bytes(joiner.id()));
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("사이클은 1주 고정 — 주 중간 입장이면 판정은 다음 사이클 경계부터")
        void countFromNextCycleBoundary() throws Exception {
            Member owner = member(uniq("cycle-owner"));
            Member joiner = member(uniq("cycle-joiner"));
            UUID challengeId = openGroup(owner.id());
            // 사이클 경계는 KST 기준으로 계산되므로 픽스처도 KST 로 잡는다.
            LocalDate start = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(3);
            jdbcTemplate.update("UPDATE challenges SET start_date = ? WHERE id = ?", start, bytes(challengeId));

            MvcResult res = join(joiner.token(), challengeId);
            assertThat((String) read(res, "$.data.countFromCycle")).isEqualTo(start.plusDays(7).toString());
        }

        @Test
        @DisplayName("비공개 방 직접 가입 → PRIVATE_INVITE_ONLY (초대 링크로만)")
        void privateInviteOnly() throws Exception {
            Member owner = member(uniq("gate-priv-owner"));
            Member joiner = member(uniq("gate-priv-joiner"));
            UUID challengeId = openGroup(owner.id());
            jdbcTemplate.update("UPDATE challenges SET visibility = 'PRIVATE' WHERE id = ?", (Object) bytes(challengeId));

            expectBlocked(join(joiner.token(), challengeId), "PRIVATE_INVITE_ONLY");
        }

        @Test
        @DisplayName("동시 참여 3개 초과 → FREE_LIMIT")
        void freeLimit() throws Exception {
            Member owner = member(uniq("gate-limit-owner"));
            Member joiner = member(uniq("gate-limit-joiner"));
            UUID challengeId = openGroup(owner.id());
            setCounter(joiner.id(), 3);

            expectBlocked(join(joiner.token(), challengeId), "FREE_LIMIT");
        }

        @Test
        @DisplayName("정원 마감 → FULL")
        void full() throws Exception {
            Member owner = member(uniq("gate-full-owner"));
            Member joiner = member(uniq("gate-full-joiner"));
            UUID challengeId = openGroup(owner.id());
            jdbcTemplate.update("UPDATE challenges SET capacity = 1 WHERE id = ?", (Object) bytes(challengeId));

            expectBlocked(join(joiner.token(), challengeId), "FULL");
        }

        @Test
        @DisplayName("표시 티어 < minTier → TIER_GATE (구 매너온도 게이트 대체)")
        void tierGate() throws Exception {
            Member owner = member(uniq("gate-tier-owner"));
            Member joiner = member(uniq("gate-tier-joiner"));
            UUID challengeId = openGroup(owner.id());
            jdbcTemplate.update("UPDATE challenges SET min_tier = 'GOLD' WHERE id = ?", (Object) bytes(challengeId));

            expectBlocked(join(joiner.token(), challengeId), "TIER_GATE");
        }

        @Test
        @DisplayName("종료된 챌린지 → CHALLENGE_COMPLETED / 이미 멤버 → ALREADY_JOINED")
        void completedAndAlreadyJoined() throws Exception {
            Member owner = member(uniq("gate-done-owner"));
            Member joiner = member(uniq("gate-done-joiner"));
            UUID challengeId = openGroup(owner.id());

            assertThat(join(joiner.token(), challengeId).getResponse().getStatus()).isEqualTo(200);
            expectBlocked(join(joiner.token(), challengeId), "ALREADY_JOINED");

            jdbcTemplate.update("UPDATE challenges SET status = 'COMPLETED' WHERE id = ?", (Object) bytes(challengeId));
            Member other = member(uniq("gate-done-other"));
            expectBlocked(join(other.token(), challengeId), "CHALLENGE_COMPLETED");
        }

        @Test
        @DisplayName("기기 권한은 서버 게이트가 아니다 — 자동 인증 방도 권한 없이 가입되고 필요 권한만 안내한다")
        void devicePermissionIsNotAServerGate() throws Exception {
            Member owner = member(uniq("gate-perm-owner"));
            Member joiner = member(uniq("gate-perm-joiner"));
            UUID challengeId = openGroup(owner.id());
            jdbcTemplate.update("UPDATE challenges SET verification_config = ? WHERE id = ?",
                    "{\"selectedMethod\":\"AUTO\",\"verificationType\":\"PHONE\",\"signalSource\":\"USAGE\","
                            + "\"wearableReq\":\"NONE\",\"requiredPermissions\":[\"PACKAGE_USAGE_STATS\"]}",
                    bytes(challengeId));

            MvcResult res = join(joiner.token(), challengeId);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.requiredPermissions[0]")).isEqualTo("PACKAGE_USAGE_STATS");
            assertThat((Boolean) read(res, "$.data.personalSetupRequired")).isTrue();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("탈퇴와 재입장")
    class LeaveAndRejoin {

        @Test
        @DisplayName("자진 탈퇴 → 감점 + 재입장 1주 대기, 그 안에 재가입하면 REJOIN_COOLDOWN")
        void leaveThenCooldown() throws Exception {
            Member owner = member(uniq("leave-owner"));
            Member joiner = member(uniq("leave-joiner"));
            UUID challengeId = openGroup(owner.id());
            join(joiner.token(), challengeId);

            MvcResult res = leave(joiner.token(), challengeId);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.left")).isTrue();
            assertThat((Integer) read(res, "$.data.scoreDelta")).isNegative();
            assertThat((Object) read(res, "$.data.exemptReason")).isNull();
            assertThat((String) read(res, "$.data.rejoinAvailableAt")).isNotBlank();
            assertThat((Boolean) read(res, "$.data.botOwnerActivated")).isFalse();

            // 카운터 해제 확인 — 안 풀리면 3개 한도를 계속 잡아먹는다
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT active_join_count FROM user_challenge_counters WHERE user_id = ?",
                    Integer.class, bytes(joiner.id()));
            assertThat(count).isZero();

            MvcResult blocked = join(joiner.token(), challengeId);
            expectBlocked(blocked, "REJOIN_COOLDOWN");
            assertThat((String) read(blocked, "$.error.rejoinAvailableAt")).isNotBlank();
        }

        @Test
        @DisplayName("강퇴 대기는 사유와 무관하게 배수로만 계산된다 — 영구 차단 경로는 없다 (정책 §10.2)")
        void kickCooldownHasNoPermanentBan() throws Exception {
            Member owner = member(uniq("kick-owner"));
            Member target = member(uniq("kick-target"));
            UUID challengeId = openGroup(owner.id());
            join(target.token(), challengeId);

            MvcResult kick = mvc.perform(delete("/api/v1/challenges/" + challengeId + "/members/" + target.id())
                    .header("Authorization", "Bearer " + owner.token())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"반복적인 규칙 위반으로 내보냅니다\"}")).andReturn();
            assertThat(kick.getResponse().getStatus()).isEqualTo(200);

            // 대기 중에는 막히고
            expectBlocked(join(target.token(), challengeId), "REJOIN_COOLDOWN");
            // 배수가 지나면 언제든 다시 들어올 수 있다(영구 차단 없음)
            jdbcTemplate.update("UPDATE challenge_members SET rejoin_available_at = DATE_SUB(NOW(6), INTERVAL 1 HOUR) "
                    + "WHERE challenge_id = ? AND user_id = ?", bytes(challengeId), bytes(target.id()));
            assertThat(join(target.token(), challengeId).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("1주가 지나면 같은 방에 다시 들어갈 수 있다 (구 '재참여 영구 불가' 폐기)")
        void rejoinAfterCooldown() throws Exception {
            Member owner = member(uniq("rejoin-owner"));
            Member joiner = member(uniq("rejoin-joiner"));
            UUID challengeId = openGroup(owner.id());
            join(joiner.token(), challengeId);
            leave(joiner.token(), challengeId);

            jdbcTemplate.update("UPDATE challenge_members SET rejoin_available_at = DATE_SUB(NOW(6), INTERVAL 1 HOUR) "
                    + "WHERE challenge_id = ? AND user_id = ?", bytes(challengeId), bytes(joiner.id()));

            assertThat(join(joiner.token(), challengeId).getResponse().getStatus()).isEqualTo(200);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("방장 승계")
    class Succession {

        @Test
        @DisplayName("방장이 넘기지 않고 나가면 즉시 봇방장 체제 — 멤버가 선착순으로 방장이 된다")
        void ownerLeavesThenBotOwnerThenClaim() throws Exception {
            Member owner = member(uniq("succ-owner"));
            Member joiner = member(uniq("succ-joiner"));
            UUID challengeId = openGroup(owner.id());
            join(joiner.token(), challengeId);

            MvcResult res = leave(owner.token(), challengeId);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.botOwnerActivated")).isTrue();

            String ownerType = jdbcTemplate.queryForObject(
                    "SELECT owner_type FROM challenges WHERE id = ?", String.class, bytes(challengeId));
            assertThat(ownerType).isEqualTo("BOT");

            MvcResult claim = postJsonAuth("/api/v1/challenges/" + challengeId + "/owner/claim",
                    joiner.token(), Map.of());
            assertThat(claim.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(claim, "$.data.myRole")).isEqualTo("OWNER");
            assertThat((String) read(claim, "$.data.graceUntil")).isNotBlank();
        }

        @Test
        @DisplayName("봇방장 체제가 된 방은 잔류 멤버 전원이 3일 면책 — 방장이 아니어도 감점 없음(정책 §11.3 모든 멤버 기준)")
        void botOwnerGraceCoversEveryMember() throws Exception {
            Member owner = member(uniq("botgrace-owner"));
            Member joiner = member(uniq("botgrace-joiner"));
            Member bystander = member(uniq("botgrace-bystander"));
            UUID challengeId = openGroup(owner.id());
            join(joiner.token(), challengeId);
            join(bystander.token(), challengeId);

            leave(owner.token(), challengeId);   // 넘기지 않고 나감 → 봇방장 전환

            MvcResult res = leave(bystander.token(), challengeId);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.exemptReason")).isEqualTo("SUCCESSION_GRACE");
            assertThat((Integer) read(res, "$.data.scoreDelta")).isZero();
        }

        @Test
        @DisplayName("봇방장 전환 3일이 지나면 면책이 끝나 일반 탈퇴와 동일하게 감점")
        void botOwnerGraceExpires() throws Exception {
            Member owner = member(uniq("expire-owner"));
            Member joiner = member(uniq("expire-joiner"));
            UUID challengeId = openGroup(owner.id());
            join(joiner.token(), challengeId);
            leave(owner.token(), challengeId);

            jdbcTemplate.update("UPDATE challenges SET owner_granted_at = DATE_SUB(NOW(6), INTERVAL 4 DAY) "
                    + "WHERE id = ?", (Object) bytes(challengeId));

            MvcResult res = leave(joiner.token(), challengeId);
            assertThat((Object) read(res, "$.data.exemptReason")).isNull();
            assertThat((Integer) read(res, "$.data.scoreDelta")).isNegative();
        }

        @Test
        @DisplayName("나가면서 스스로 만든 봇방장 전환으로 자기 감점을 면제받지는 못한다")
        void leavingOwnerDoesNotSelfExempt() throws Exception {
            Member owner = member(uniq("selfexempt-owner"));
            Member joiner = member(uniq("selfexempt-joiner"));
            UUID challengeId = openGroup(owner.id());
            join(joiner.token(), challengeId);

            MvcResult res = leave(owner.token(), challengeId);
            assertThat((Boolean) read(res, "$.data.botOwnerActivated")).isTrue();
            assertThat((Object) read(res, "$.data.exemptReason")).isNull();
            assertThat((Integer) read(res, "$.data.scoreDelta")).isNegative();
        }

        @Test
        @DisplayName("선착순으로 방장이 된 사람은 3일 안에 나가면 감점 없음(SUCCESSION_GRACE)")
        void claimGraceExemption() throws Exception {
            Member owner = member(uniq("grace-owner"));
            Member joiner = member(uniq("grace-joiner"));
            UUID challengeId = openGroup(owner.id());
            join(joiner.token(), challengeId);
            leave(owner.token(), challengeId);
            postJsonAuth("/api/v1/challenges/" + challengeId + "/owner/claim", joiner.token(), Map.of());

            MvcResult res = leave(joiner.token(), challengeId);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.exemptReason")).isEqualTo("SUCCESSION_GRACE");
            assertThat((Integer) read(res, "$.data.scoreDelta")).isZero();
        }

        @Test
        @DisplayName("직접 넘겨받은 방장은 면책 대상이 아니다 — 일반 탈퇴와 동일하게 감점")
        void transferHasNoGrace() throws Exception {
            Member owner = member(uniq("nograce-owner"));
            Member joiner = member(uniq("nograce-joiner"));
            UUID challengeId = openGroup(owner.id());
            join(joiner.token(), challengeId);

            MvcResult transfer = patchJsonAuth("/api/v1/challenges/" + challengeId + "/owner", owner.token(),
                    Map.of("targetUserId", joiner.id().toString()));
            assertThat(transfer.getResponse().getStatus()).isEqualTo(200);

            MvcResult res = leave(joiner.token(), challengeId);
            assertThat((Object) read(res, "$.data.exemptReason")).isNull();
            assertThat((Integer) read(res, "$.data.scoreDelta")).isNegative();
        }
    }
}
