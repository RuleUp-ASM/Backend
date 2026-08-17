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

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 방 스레드·방 홈의 Phase 1 응답 계약 — 가드레일 2건과 "판정 이력 없음" 표기.
 *
 * <p>기능 스펙 3-3 의 절대 조건 두 가지가 여기서 검증된다.
 * <ul>
 *   <li><b>이의 기간 내 실패 조기 노출 0건</b> — shareableAt 이 비었거나 미래면 피드에 없어야 한다.</li>
 *   <li><b>차단 유저 콘텐츠 노출 0건</b> — 인증 이벤트·랭킹 모두 임시 닉네임·기본 이미지로 가려야 한다.
 *       스레드에서는 목록에서 빼지 않고 가린 채로 남긴다(빼면 피드에 구멍이 생겨 맥락이 무너진다).</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoomPhase1ContractIT extends ChallengeApiSupport {

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
    @DisplayName("이의 기간이 남은 실패는 피드에 없고, 기간이 지나야 원래 날짜와 함께 뜬다")
    void failEventStaysHiddenUntilShareable() throws Exception {
        Member owner = member(uniq("guard-owner"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");

        // ① 잠정 실패 — 이의 기간 중이라 shareableAt 이 비어 있다
        UUID failId = insertVerification(challengeId, owner.id(), "FAILED", 3, null);
        assertThat(items(threads(challengeId, owner.token()))).isEmpty();

        // ② 이의 기간이 아직 안 끝났다(공유 가능 시각이 미래)
        setShareableAt(failId, "DATE_ADD(NOW(6), INTERVAL 1 HOUR)");
        assertThat(items(threads(challengeId, owner.token()))).isEmpty();

        // ③ 기간 경과 — 이제야 뜨고, 노출 시각이 아니라 실패한 날짜를 failDate 로 준다
        setShareableAt(failId, "DATE_SUB(NOW(6), INTERVAL 1 MINUTE)");
        JsonNode items = items(threads(challengeId, owner.token()));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("type").asText()).isEqualTo("VERIFY_FAIL");
        assertThat(items.get(0).path("failDate").asText())
                .isEqualTo(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(3).toString());
    }

    @Test
    @DisplayName("차단한 유저의 인증 이벤트는 목록에 남되 임시 닉네임·기본 이미지로 가려진다")
    void blockedAuthorIsMaskedNotRemoved() throws Exception {
        Member viewer = member(uniq("mask-viewer"));
        Member blocked = member(uniq("mask-blocked"));
        UUID challengeId = insertChallenge(viewer.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, viewer.id(), "OWNER");
        insertActiveMembership(challengeId, blocked.id(), "MEMBER");
        insertVerification(challengeId, blocked.id(), "SUCCESS", 0, PAST);
        block(viewer, blocked, challengeId);

        JsonNode items = items(threads(challengeId, viewer.token()));
        assertThat(items).hasSize(1);
        JsonNode user = items.get(0).path("user");
        assertThat(user.path("userId").asText()).isEqualTo(blocked.id().toString());
        assertThat(user.path("blocked").asBoolean()).isTrue();
        assertThat(user.path("profileImageUrl").isNull()).isTrue();
        // 닉네임을 비우면 클라가 빈 줄을 그린다 — 스펙대로 임시 닉네임(계정 id 끝 8자)을 준다
        assertThat(user.path("nickname").asText()).isEqualTo(tempNicknameOf(blocked.id()));
    }

    @Test
    @DisplayName("차단한 유저는 방 안 랭킹과 상위 3에서도 임시 닉네임으로 가려진다")
    void blockedUserIsMaskedInRanking() throws Exception {
        Member viewer = member(uniq("rank-viewer"));
        Member blocked = member(uniq("rank-blocked"));
        UUID challengeId = insertChallenge(viewer.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, viewer.id(), "OWNER");
        insertActiveMembership(challengeId, blocked.id(), "MEMBER");
        setDays(challengeId, viewer.id(), 5, 5);
        setDays(challengeId, blocked.id(), 10, 0);   // 1위 — 상위 3에도 반드시 들어온다
        block(viewer, blocked, challengeId);

        JsonNode ranking = data(getAuth("/api/v1/challenges/" + challengeId + "/ranking", viewer.token()));
        JsonNode blockedRow = rowOf(ranking.path("items"), blocked.id());
        assertThat(blockedRow.path("user").path("nickname").asText()).isEqualTo(tempNicknameOf(blocked.id()));
        assertThat(blockedRow.path("user").path("profileImageUrl").isNull()).isTrue();
        assertThat(blockedRow.path("user").path("blocked").asBoolean()).isTrue();

        JsonNode top = data(getAuth("/api/v1/challenges/" + challengeId + "/room", viewer.token())).path("topRanking");
        assertThat(top.get(0).path("userId").asText()).isEqualTo(blocked.id().toString());
        assertThat(top.get(0).path("nickname").asText()).isEqualTo(tempNicknameOf(blocked.id()));
        assertThat(top.get(0).path("blocked").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("차단한 유저는 멤버 목록에서도 실제 닉네임·사진이 노출되지 않는다")
    void blockedUserIsMaskedInMemberList() throws Exception {
        Member viewer = member(uniq("members-viewer"));
        Member blocked = member(uniq("members-blocked"));
        UUID challengeId = insertChallenge(viewer.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, viewer.id(), "OWNER");
        insertActiveMembership(challengeId, blocked.id(), "MEMBER");
        block(viewer, blocked, challengeId);

        JsonNode data = data(getAuth("/api/v1/challenges/" + challengeId + "/members", viewer.token()));
        assertThat(data.path("challengeId").asText()).isEqualTo(challengeId.toString());
        assertThat(data.path("participantCount").asInt()).isEqualTo(2);
        assertThat(data.path("capacity").asInt()).isEqualTo(50);
        JsonNode blockedRow = null;
        for (JsonNode member : data.path("members"))
            if (blocked.id().toString().equals(member.path("userId").asText())) blockedRow = member;
        assertThat(blockedRow).isNotNull();
        assertThat(blockedRow.path("blocked").asBoolean()).isTrue();
        assertThat(blockedRow.path("nickname").asText()).isEqualTo(tempNicknameOf(blocked.id()));
        assertThat(blockedRow.path("profileImageUrl").isNull()).isTrue();
    }

    @Test
    @DisplayName("성공 이벤트의 streak는 현재 누적 성공일이 아니라 그 이벤트 시점의 연속 성공 횟수다")
    void streakIsCalculatedAtEventTime() throws Exception {
        Member owner = member(uniq("streak-owner"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        UUID oldest = insertTimedVerification(challengeId, owner.id(), "SUCCESS", 3, 4);
        UUID second = insertTimedVerification(challengeId, owner.id(), "SUCCESS", 2, 3);
        insertTimedVerification(challengeId, owner.id(), "FAILED", 1, 2);
        UUID latest = insertTimedVerification(challengeId, owner.id(), "SUCCESS", 0, 1);

        JsonNode rows = items(threads(challengeId, owner.token()));
        assertThat(rows).hasSize(4);
        assertThat(rowById(rows, latest).path("streak").asInt()).isEqualTo(1);
        assertThat(rowById(rows, second).path("streak").asInt()).isEqualTo(2);
        assertThat(rowById(rows, oldest).path("streak").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("판정 이력이 하나도 없는 방의 성공률은 0이 아니라 null이다")
    void roomSuccessRateIsNullBeforeAnyJudgement() throws Exception {
        Member owner = member(uniq("rate-owner"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");

        JsonNode summary = data(getAuth("/api/v1/challenges/" + challengeId + "/room", owner.token()))
                .path("summary");
        // 0.0 으로 내려보내면 "전원 실패한 방"과 구분되지 않는다
        assertThat(summary.get("roomSuccessRate").isNull()).isTrue();

        setDays(challengeId, owner.id(), 8, 2);
        summary = data(getAuth("/api/v1/challenges/" + challengeId + "/room", owner.token())).path("summary");
        assertThat(summary.path("roomSuccessRate").asDouble()).isEqualTo(0.8d);
    }

    // ===== 헬퍼 =====

    /**
     * "확실히 지난 시각". DB 컨테이너와 JVM 의 시계는 수십 ms 어긋날 수 있어, NOW(6) 로 심은 이벤트가
     * 서버 입장에서 미래로 보여 걸러지는 일이 있다. 노출 여부를 다루는 테스트라 이 흔들림을 없앤다.
     */
    private static final String PAST = "DATE_SUB(NOW(6), INTERVAL 1 MINUTE)";

    private MvcResult threads(UUID challengeId, String token) throws Exception {
        return getAuth("/api/v1/challenges/" + challengeId + "/threads", token);
    }

    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return OM.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode items(MvcResult result) throws Exception {
        return data(result).path("items");
    }

    private JsonNode rowOf(JsonNode items, UUID userId) {
        for (JsonNode item : items) {
            if (userId.toString().equals(item.path("user").path("userId").asText())) return item;
        }
        throw new AssertionError("랭킹에 " + userId + " 가 없다");
    }

    private JsonNode rowById(JsonNode items, UUID id) {
        for (JsonNode item : items) if (id.toString().equals(item.path("id").asText())) return item;
        throw new AssertionError("스레드에 " + id + " 가 없다");
    }

    private void block(Member reporter, Member target, UUID challengeId) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/reports")
                .header("Authorization", "Bearer " + reporter.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("targetType", "USER",
                        "targetUserId", target.id().toString(), "contextType", "ROOM",
                        "targetChallengeId", challengeId.toString(),
                        "reason", "ABUSE", "detail", "반복적인 모욕적인 표현입니다.")))).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
    }

    private void setDays(UUID challengeId, UUID userId, int success, int fail) {
        jdbcTemplate.update("UPDATE challenge_members SET success_days=?, fail_days=? " +
                "WHERE challenge_id=? AND user_id=?", success, fail, bytes(challengeId), bytes(userId));
    }

    /**
     * 확정 판정 1건을 심는다. daysAgo 는 targetDate 기준. shareableSql 이 null 이면 미공유 상태.
     * 확정 시각(verifiedAt)은 항상 {@link #PAST} 다 — 아래 상수 주석 참고.
     */
    private UUID insertVerification(UUID challengeId, UUID userId, String status, int daysAgo, String shareableSql) {
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM challenge_members WHERE challenge_id=? AND user_id=?",
                (rs, i) -> uuidOf(rs.getBytes(1)), bytes(challengeId), bytes(userId));
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO VerificationDaily " +
                        "(id, challengeMemberId, challengeId, userId, targetDate, status, verifiedAt, shareableAt) " +
                        "VALUES (?, ?, ?, ?, DATE_SUB(CURDATE(), INTERVAL ? DAY), ?, " + PAST + ", "
                        + (shareableSql == null ? "NULL" : shareableSql) + ")",
                bytes(id), bytes(memberId), bytes(challengeId), bytes(userId), daysAgo, status);
        return id;
    }

    private UUID insertTimedVerification(UUID challengeId, UUID userId, String status,
                                         int daysAgo, int minutesAgo) {
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM challenge_members WHERE challenge_id=? AND user_id=?",
                (rs, i) -> uuidOf(rs.getBytes(1)), bytes(challengeId), bytes(userId));
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO VerificationDaily " +
                        "(id,challengeMemberId,challengeId,userId,targetDate,status,verifiedAt,shareableAt) " +
                        "VALUES (?,?,?,?,DATE_SUB(CURDATE(), INTERVAL ? DAY),?," +
                        "DATE_SUB(NOW(6), INTERVAL ? MINUTE),DATE_SUB(NOW(6), INTERVAL ? MINUTE))",
                bytes(id), bytes(memberId), bytes(challengeId), bytes(userId), daysAgo, status,
                minutesAgo, minutesAgo);
        return id;
    }

    private void setShareableAt(UUID verificationId, String sqlExpression) {
        jdbcTemplate.update("UPDATE VerificationDaily SET shareableAt=" + sqlExpression + " WHERE id=?",
                bytes(verificationId));
    }

    /** 타인에게 노출되는 임시 닉네임 = 계정 id 의 마지막 8자(User.deriveTempNickname). */
    private static String tempNicknameOf(UUID userId) {
        String hex = userId.toString().replace("-", "");
        return hex.substring(hex.length() - 8);
    }

    private static UUID uuidOf(byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
