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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 방 내부·운영 정책 → 테크스펙 → API 문서 순서로 고정한 회귀 계약 테스트. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoomOperationsApiIT extends ChallengeApiSupport {

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
    @DisplayName("방 피드는 ACTIVE 멤버만 조회할 수 있고 비멤버는 NOT_CHALLENGE_MEMBER다")
    void threadsRequireActiveMember() throws Exception {
        Member owner = member(uniq("thread-owner"));
        Member outsider = member(uniq("thread-outsider"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");

        MvcResult allowed = getAuth("/api/v1/challenges/" + challengeId + "/threads", owner.token());
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) read(allowed, "$.data.items.length()")) .isZero();

        expectError(getAuth("/api/v1/challenges/" + challengeId + "/threads", outsider.token()),
                403, "NOT_CHALLENGE_MEMBER");
    }

    @Test
    @DisplayName("방장 위임은 수락 절차 없이 OWNER 역할을 원자적으로 교체한다")
    void ownerTransferIsImmediate() throws Exception {
        Member owner = member(uniq("owner-old"));
        Member target = member(uniq("owner-new"));
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
    @DisplayName("사용자 신고는 즉시 개인 차단에 반영되고 해제 API로 제거된다")
    void reportImmediatelyBlacklistsTarget() throws Exception {
        Member reporter = member(uniq("reporter"));
        Member target = member(uniq("reported"));

        MvcResult report = postJson("/api/v1/reports", reporter.token(), Map.of(
                "targetType", "USER", "targetUserId", target.id().toString(),
                "contextType", "PROFILE", "reason", "ABUSE", "detail", "반복적인 모욕적인 표현입니다."));
        assertThat(report.getResponse().getStatus()).isEqualTo(201);
        assertThat((Boolean) read(report, "$.data.blacklisted")).isTrue();

        MvcResult blacklist = getAuth("/api/v1/users/me/blacklist", reporter.token());
        assertThat((String) read(blacklist, "$.data.users[0].userId")).isEqualTo(target.id().toString());

        MvcResult removed = mvc.perform(delete("/api/v1/users/me/blacklist/users/" + target.id())
                .header("Authorization", "Bearer " + reporter.token())).andReturn();
        assertThat(removed.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) read(removed, "$.data.removed")).isTrue();
    }

    @Test
    @DisplayName("방 홈과 방 랭킹은 읽음 필드 없이 성공률·10회 최소 표본 계약을 따른다")
    void roomAndRankingUseSuccessRateContract() throws Exception {
        Member owner = member(uniq("ranking-owner"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        jdbcTemplate.update("UPDATE challenge_members SET success_days=8,fail_days=2 WHERE challenge_id=? AND user_id=?",
                bytes(challengeId), bytes(owner.id()));

        MvcResult room = getAuth("/api/v1/challenges/" + challengeId + "/room", owner.token());
        assertThat((String) read(room, "$.data.ownerType")).isEqualTo("USER");
        assertThat((Double) read(room, "$.data.summary.roomSuccessRate")).isEqualTo(0.8d);

        MvcResult ranking = getAuth("/api/v1/challenges/" + challengeId + "/ranking", owner.token());
        assertThat((Boolean) read(ranking, "$.data.me.ranked")).isTrue();
        assertThat((Integer) read(ranking, "$.data.me.rank")).isEqualTo(1);
    }

    @Test
    @DisplayName("방장은 비공개 방 초대를 만들고 멤버를 강퇴하며 ROOM 알림을 남긴다")
    void invitationKickAndNotification() throws Exception {
        Member owner = member(uniq("admin-owner"));
        Member target = member(uniq("admin-target"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        insertActiveMembership(challengeId, target.id(), "MEMBER");
        jdbcTemplate.update("UPDATE challenges SET visibility='PRIVATE' WHERE id=?", bytes(challengeId));

        MvcResult invitation = mvc.perform(post("/api/v1/challenges/" + challengeId + "/invitations")
                .header("Authorization", "Bearer " + owner.token())).andReturn();
        assertThat(invitation.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) read(invitation, "$.data.token")).isNotBlank();

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

        MvcResult notifications = getAuth("/api/v1/notifications?filter=ROOM", target.token());
        assertThat((String) read(notifications, "$.data.items[0].type")).isEqualTo("CHALLENGE_MEMBER_KICKED");
    }

    @Test
    @DisplayName("알림 설정은 부분 수정 후 동일 계약으로 조회된다")
    void notificationSettingsPatch() throws Exception {
        Member member = member(uniq("settings"));
        MvcResult patched = patchJsonAuth("/api/v1/users/me/notification-settings", member.token(),
                Map.of("roomActivity", false, "nightPush", true));
        assertThat(patched.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) read(patched, "$.data.roomActivity")).isFalse();
        assertThat((Boolean) read(patched, "$.data.nightPush")).isTrue();

        MvcResult fetched = getAuth("/api/v1/users/me/notification-settings", member.token());
        assertThat((Boolean) read(fetched, "$.data.roomActivity")).isFalse();
        assertThat((Boolean) read(fetched, "$.data.challengeActivity")).isTrue();
    }

    private MvcResult postJson(String url, String token, Map<String, ?> body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }
}
