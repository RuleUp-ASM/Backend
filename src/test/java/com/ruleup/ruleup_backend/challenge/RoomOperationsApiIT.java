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
    @DisplayName("페이지2로 밀린 방장 운영 3종은 매핑 자체가 없다 — 클라에서 감추는 것으로는 부족하다")
    void phase2OwnerAdminEndpointsAreNotMapped() throws Exception {
        Member owner = member(uniq("phase2-owner"));
        Member target = member(uniq("phase2-target"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        insertActiveMembership(challengeId, target.id(), "MEMBER");

        // 방장 위임 — 폐지(챌린지 정책 §11). 방장이 나가면 봇방장으로 자동 전환될 뿐이다.
        assertThat(mvc.perform(patch("/api/v1/challenges/" + challengeId + "/owner")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("targetUserId", target.id().toString()))))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        // 방장 승계(선착순 클레임) — 폐지. 승계가 없으므로 3일 면책 규칙도 함께 사라졌다.
        assertThat(mvc.perform(post("/api/v1/challenges/" + challengeId + "/owner/claim")
                .header("Authorization", "Bearer " + target.token()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        // 방장 재량 강퇴 — 비활성. 페이지1의 강퇴 경로는 자동 제재 3종뿐이며 전부 배치가 처리한다.
        assertThat(mvc.perform(delete("/api/v1/challenges/" + challengeId + "/members/" + target.id())
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("reason", "반복적인 운영 규칙 위반입니다."))))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        // 매핑만 빠진 것이지 멤버십이 바뀌어서는 안 된다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM challenge_members WHERE challenge_id=? AND user_id=?",
                String.class, bytes(challengeId), bytes(target.id()))).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("사용자 신고는 즉시 개인 차단에 반영되고 해제 API로 제거된다")
    void reportImmediatelyBlacklistsTarget() throws Exception {
        Member reporter = member(uniq("reporter"));
        Member target = member(uniq("reported"));

        MvcResult report = postJson("/api/v1/reports", reporter.token(), Map.of(
                "targetType", "USER", "targetUserId", target.id().toString(),
                "contextType", "PROFILE", "reason", "INAPPROPRIATE"));
        assertThat(report.getResponse().getStatus()).isEqualTo(201);
        assertThat((Boolean) read(report, "$.data.blocked")).isTrue();
        assertThat((String) read(report, "$.data.hiddenEffect")).isEqualTo("USER_CONTENT_MASKED");

        MvcResult blocks = getAuth("/api/v1/users/me/blocks", reporter.token());
        assertThat((String) read(blocks, "$.data.users[0].userId")).isEqualTo(target.id().toString());

        MvcResult profile = getAuth("/api/v1/users/" + target.id() + "/profile", reporter.token());
        String targetHex = target.id().toString().replace("-", "");
        assertThat((Boolean) read(profile, "$.data.blocked")).isTrue();
        assertThat((String) read(profile, "$.data.nickname"))
                .isEqualTo(targetHex.substring(targetHex.length() - 8));
        assertThat((Object) read(profile, "$.data.profileImageUrl")).isNull();

        MvcResult removed = mvc.perform(delete("/api/v1/users/me/blocks/users/" + target.id())
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
    @DisplayName("방장은 비공개 방 초대를 만들 수 있다 — 초대는 페이지1에 남은 유일한 방장 운영 API다")
    void invitationIssue() throws Exception {
        Member owner = member(uniq("admin-owner"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        jdbcTemplate.update("UPDATE challenges SET visibility='PRIVATE' WHERE id=?", bytes(challengeId));

        MvcResult invitation = mvc.perform(post("/api/v1/challenges/" + challengeId + "/invitations")
                .header("Authorization", "Bearer " + owner.token())).andReturn();
        assertThat(invitation.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) read(invitation, "$.data.token")).isNotBlank();
    }

    @Test
    @DisplayName("알림 설정은 유형별 토글로 저장되고 같은 계약으로 조회된다")
    void notificationSettingsPatch() throws Exception {
        Member member = member(uniq("settings"));
        MvcResult patched = patchJsonAuth("/api/v1/users/me/notification-settings", member.token(),
                Map.of("types", java.util.List.of(
                        Map.of("type", "ROUTINE_REMINDER", "enabled", false))));
        assertThat(patched.getResponse().getStatus()).isEqualTo(200);

        MvcResult fetched = getAuth("/api/v1/users/me/notification-settings", member.token());
        java.util.List<Map<String, Object>> types = read(fetched, "$.data.types");
        assertThat(types).filteredOn(t -> "ROUTINE_REMINDER".equals(t.get("type")))
                .singleElement().satisfies(t -> assertThat(t.get("enabled")).isEqualTo(false));
        // 건드리지 않은 항목은 기본 ON 그대로다 — 행이 없으면 ON 으로 해석한다.
        assertThat(types).filteredOn(t -> "TIER_CHANGED".equals(t.get("type")))
                .singleElement().satisfies(t -> assertThat(t.get("enabled")).isEqualTo(true));
    }

    private MvcResult postJson(String url, String token, Map<String, ?> body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }
}
