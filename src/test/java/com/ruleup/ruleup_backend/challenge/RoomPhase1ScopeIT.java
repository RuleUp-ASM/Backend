package com.ruleup.ruleup_backend.challenge;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 1 범위 계약 — 방 공지·댓글 API와 저장소는 제거하고 pinnedNotice:null 호환은 유지한다.
 * 운영자 공지·인앱 알림·감시자 통지 저장소는 방 공지와 별개로 보존한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoomPhase1ScopeIT extends ChallengeApiSupport {

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
    @DisplayName("공지 엔드포인트 6종은 더 이상 매핑돼 있지 않다")
    void noticeEndpointsAreGone() throws Exception {
        Member owner = member(uniq("phase1-notice"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        String base = "/api/v1/challenges/" + challengeId + "/notices";
        String one = base + "/" + UUID.randomUUID();

        assertThat(getAuth(base, owner.token()).getResponse().getStatus()).isEqualTo(404);
        assertThat(getAuth(one, owner.token()).getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post(base).header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("title", "공지", "content", "본문"))))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(put(one).header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("title", "공지", "content", "본문"))))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(delete(one).header("Authorization", "Bearer " + owner.token()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(patch(one + "/pin").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("pinned", true))))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("댓글 엔드포인트 3종은 더 이상 매핑돼 있지 않다")
    void commentEndpointsAreGone() throws Exception {
        Member member = member(uniq("phase1-comment"));

        assertThat(getAuth("/api/v1/comments?targetType=NOTICE&targetId=" + UUID.randomUUID(), member.token())
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/api/v1/comments").header("Authorization", "Bearer " + member.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("targetType", "NOTICE",
                        "targetId", UUID.randomUUID().toString(), "body", "댓글"))))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(delete("/api/v1/comments/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + member.token()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("스레드 응답의 pinnedNotice 는 Phase 1 동안 null 이다")
    void threadResponseCarriesNullPinnedNotice() throws Exception {
        Member owner = member(uniq("phase1-thread"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        insertSuccessVerification(challengeId, owner.id());

        MvcResult result = getAuth("/api/v1/challenges/" + challengeId + "/threads", owner.token());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = OM.readTree(result.getResponse().getContentAsString()).path("data");

        assertThat(data.has("pinnedNotice")).isTrue();
        assertThat(data.get("pinnedNotice").isNull()).isTrue();
        assertThat(data.path("items")).hasSize(1);
        JsonNode item = data.path("items").get(0);
        assertThat(item.path("type").asText()).isEqualTo("VERIFY_SUCCESS");
        assertThat(item.has("title")).isFalse();
        assertThat(item.has("commentCount")).isFalse();
    }

    @Test
    @DisplayName("방 홈 응답의 pinnedNotice 는 Phase 1 동안 null 이다")
    void roomResponseCarriesNullPinnedNotice() throws Exception {
        Member owner = member(uniq("phase1-room"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");

        MvcResult result = getAuth("/api/v1/challenges/" + challengeId + "/room", owner.token());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = OM.readTree(result.getResponse().getContentAsString()).path("data");

        assertThat(data.has("pinnedNotice")).isTrue();
        assertThat(data.get("pinnedNotice").isNull()).isTrue();
        assertThat(data.has("unreadNoticeCount")).isFalse();
        assertThat(data.has("myRole")).isTrue();   // 나머지 계약은 그대로여야 한다
    }

    @Test
    @DisplayName("방 공지·댓글 테이블만 제거하고 운영자 공지·알림·감시자·활동 로그는 보존한다")
    void roomNoticeTablesAreRemovedWithoutAffectingAnnouncements() {
        assertThat(tableExists("Notice")).isFalse();
        assertThat(tableExists("NoticeRead")).isFalse();
        assertThat(tableExists("room_comments")).isFalse();
        assertThat(tableExists("announcements")).isTrue();
        assertThat(tableExists("notifications")).isTrue();
        assertThat(tableExists("watcher_notices")).isTrue();
        assertThat(tableExists("RoomActivityLog")).isTrue();
    }

    private boolean tableExists(String table) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name = ?", Integer.class, table);
        return n != null && n > 0;
    }

    /**
     * 스레드에 뜰 성공 이벤트 1건. 인증 모듈을 돌리지 않고 확정 결과만 심는다.
     * 확정 시각을 1분 전으로 두는 이유: DB 컨테이너와 JVM 의 시계가 수십 ms 어긋날 수 있어
     * NOW(6) 로 넣으면 서버가 "아직 오지 않은 이벤트"로 보고 걸러낸다.
     */
    private void insertSuccessVerification(UUID challengeId, UUID userId) {
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM challenge_members WHERE challenge_id=? AND user_id=?",
                (rs, i) -> uuidOf(rs.getBytes(1)), bytes(challengeId), bytes(userId));
        jdbcTemplate.update("INSERT INTO VerificationDaily " +
                        "(id, challengeMemberId, challengeId, userId, targetDate, status, verifiedAt, shareableAt) " +
                        "VALUES (?, ?, ?, ?, DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), 'SUCCESS', " +
                        "DATE_SUB(NOW(6), INTERVAL 1 MINUTE), DATE_SUB(NOW(6), INTERVAL 1 MINUTE))",
                bytes(UUID.randomUUID()), bytes(memberId), bytes(challengeId), bytes(userId));
    }

    private static UUID uuidOf(byte[] raw) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
