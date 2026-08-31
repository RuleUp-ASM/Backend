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
 * Phase 1 범위 계약 — 공지·댓글 API는 비활성화하되 호환 필드와 저장소는 보존한다.
 *
 * <p>기능 스펙 6-2 #9·#10(2026-08-12 범위 조정)에 따라 공지 일체와 댓글·답글은 이번 범위에서 빠졌다.
 * API 비활성화와 저장소 삭제는 다른 결정이다. Phase 1에서는 엔드포인트를 열지 않되 재개 가능한 저장소와
 * `pinnedNotice:null` 응답 호환을 함께 고정한다.
 *
 * <p>재개(Phase 2) 시점에는 이 테스트가 통째로 실패하는 것이 정상이며, 그때 삭제하면 된다.
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
    @DisplayName("Phase 2 재개를 위한 공지·댓글 테이블과 단일 고정 공지 제약은 보존된다")
    void phase2TablesArePreserved() {
        assertThat(tableExists("Notice")).isTrue();
        assertThat(tableExists("NoticeRead")).isTrue();
        assertThat(tableExists("room_comments")).isTrue();
        assertThat(tableExists("RoomActivityLog")).isTrue();

        Integer pinConstraint = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() " +
                        "AND table_name='Notice' AND index_name='uqNoticeOneActivePin' AND non_unique=0",
                Integer.class);
        assertThat(pinConstraint).isEqualTo(1);

        // 공지·댓글 알림 5종은 Phase 2 이관분이라 레지스트리에 아직 없다. 알림 타입이 VARCHAR 라
        // 롤백 후 옛 값이 남을 수 있으므로 적재분이 없는지도 함께 본다.
        assertThat(com.ruleup.ruleup_backend.notification.domain.NotificationType.find("NOTICE_CREATED"))
                .isEmpty();
        Integer leftovers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE type IN ('NOTICE_CREATED','COMMENT_CREATED')",
                Integer.class);
        assertThat(leftovers).isZero();
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
