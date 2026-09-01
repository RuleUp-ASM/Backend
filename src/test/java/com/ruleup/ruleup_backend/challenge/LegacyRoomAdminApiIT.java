package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * 페이지2로 밀린 방장 운영 3종의 <b>보존 계약</b>.
 *
 * <p>기본 프로파일에서 이 API 들이 404 라는 것은 {@link RoomOperationsApiIT} 가 지킨다. 여기서는
 * 반대쪽을 지킨다 — <b>플래그를 켜면 예전 동작이 그대로 살아난다.</b> 명세를 "재개 시 그대로 쓰기
 * 위해 보존"하기로 한 이상, 보존된 것이 실제로 동작하는지도 함께 남겨 둬야 재개할 때 믿을 수 있다.
 *
 * <p>서비스 계층({@code RoomAdminService})은 플래그와 무관하게 계속 살아 있다 — 자동 제재 3종에
 * 따른 강퇴가 같은 코드를 쓰기 때문이다. 빠진 것은 <b>HTTP 매핑</b>뿐이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.features.room-owner-admin.enabled=true")
class LegacyRoomAdminApiIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("플래그를 켜면 방장 위임이 수락 절차 없이 OWNER 역할을 원자적으로 교체한다")
    void ownerTransferIsImmediate() throws Exception {
        Member owner = member(uniq("legacy-owner-old"));
        Member target = member(uniq("legacy-owner-new"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        insertActiveMembership(challengeId, target.id(), "MEMBER");

        MvcResult response = mvc.perform(patch("/api/v1/challenges/" + challengeId + "/owner")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("targetUserId", target.id().toString())))).andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) read(response, "$.data.newOwnerUserId")).isEqualTo(target.id().toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM challenge_members WHERE challenge_id=? AND user_id=?",
                String.class, bytes(challengeId), bytes(target.id()))).isEqualTo("OWNER");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM challenge_members WHERE challenge_id=? AND user_id=?",
                String.class, bytes(challengeId), bytes(owner.id()))).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("플래그를 켜면 강퇴가 재입장 대기 1주와 필수(A) 알림을 남긴다")
    void kickAndNotification() throws Exception {
        Member owner = member(uniq("legacy-kick-owner"));
        Member target = member(uniq("legacy-kick-target"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        insertActiveMembership(challengeId, target.id(), "MEMBER");

        MvcResult kicked = mvc.perform(delete("/api/v1/challenges/" + challengeId + "/members/" + target.id())
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("reason", "반복적인 운영 규칙 위반입니다.")))).andReturn();
        assertThat((Boolean) read(kicked, "$.data.kicked")).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM challenge_members WHERE challenge_id=? AND user_id=?",
                String.class, bytes(challengeId), bytes(target.id()))).isEqualTo("REMOVED");
        // 재입장 대기는 1주 → 2주 → 4주 매번 두 배(제재 정책 §4.3). 첫 강퇴이므로 1주.
        Integer waitDays = jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(DAY, left_at, rejoin_available_at) FROM challenge_members " +
                        "WHERE challenge_id=? AND user_id=?",
                Integer.class, bytes(challengeId), bytes(target.id()));
        assertThat(waitDays).isEqualTo(7);

        // 강퇴 확정은 필수(A) — 야간에도 즉시 나가고 끌 수 없다.
        // 아웃박스를 거치지만 커밋 직후 즉시 흘리므로 응답 시점에는 이미 적재돼 있다.
        MvcResult notifications = getAuth("/api/v1/notifications", target.token());
        assertThat((String) read(notifications, "$.data.items[0].type")).isEqualTo("CHALLENGE_KICKED");
        assertThat((String) read(notifications, "$.data.items[0].category")).isEqualTo("A");
    }

    // ===== 헬퍼 — ChallengeJoinGateIT 에서 함께 옮겨 왔다 =====

    private MvcResult join(String token, UUID challengeId) throws Exception {
        return postJsonAuth("/api/v1/challenges/" + challengeId + "/members", token, Map.of());
    }

    private MvcResult leave(String token, UUID challengeId) throws Exception {
        return mvc.perform(delete("/api/v1/challenges/" + challengeId + "/members/me")
                .header("Authorization", "Bearer " + token)).andReturn();
    }

    /** 방장 1명이 이미 들어 있는 공개 그룹 방. */
    private UUID openGroup(UUID ownerId) {
        UUID challengeId = insertChallenge(ownerId, "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, ownerId, "OWNER");
        jdbcTemplate.update("UPDATE challenges SET visibility = 'PUBLIC' WHERE id = ?",
                (Object) bytes(challengeId));
        return challengeId;
    }

    private void expectBlocked(MvcResult res, String reason) throws Exception {
        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) read(res, "$.error.code")).isEqualTo("JOIN_BLOCKED");
        assertThat((String) read(res, "$.error.reason")).isEqualTo(reason);
    }

    private int versionOf(UUID challengeId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT version FROM challenges WHERE id = ?", Integer.class, bytes(challengeId));
        return v != null ? v : 0;
    }

    // ===== 페이지2로 밀린 동작들 — 플래그를 켠 채로 계약을 보존한다 =====

    @Test
    @DisplayName("플래그를 켜면 봇방장 방에서 선착순 클레임이 3일 면책과 함께 성립한다")
    void botOwnerClaim() throws Exception {
        Member owner = member(uniq("legacy-claim-owner"));
        Member joiner = member(uniq("legacy-claim-joiner"));
        UUID challengeId = openGroup(owner.id());
        join(joiner.token(), challengeId);

        assertThat(leave(owner.token(), challengeId).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_type FROM challenges WHERE id = ?", String.class, bytes(challengeId)))
                .isEqualTo("BOT");

        MvcResult claim = postJsonAuth("/api/v1/challenges/" + challengeId + "/owner/claim",
                joiner.token(), Map.of());
        assertThat(claim.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) read(claim, "$.data.myRole")).isEqualTo("OWNER");
        assertThat((String) read(claim, "$.data.graceUntil")).isNotBlank();
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
    @DisplayName("강퇴로 인원이 줄어도 version 이 오른다")
    void kickBumpsVersion() throws Exception {
        Member owner = member(uniq("kick-ver-owner"));
        Member target = member(uniq("kick-ver-target"));
        UUID challengeId = openGroup(owner.id());
        join(target.token(), challengeId);
        int before = versionOf(challengeId);

        MvcResult res = mvc.perform(delete(
                        "/api/v1/challenges/" + challengeId + "/members/" + target.id())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OM.writeValueAsString(Map.of("reason", "규칙을 반복해서 어겼습니다"))))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        assertThat(versionOf(challengeId)).isGreaterThan(before);
    }
}
