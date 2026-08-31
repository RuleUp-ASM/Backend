package com.ruleup.ruleup_backend.challenge;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 스레드 커서 페이징 계약.
 *
 * <p>현재 구현은 방의 확정 판정을 전부 읽어 메모리에서 정렬·절단하는 <b>레거시 방식</b>이다
 * (백엔드 테크 스펙 4-3 은 {@code (created_at, id)} 복합 커서 SQL 을 요구한다).
 * 언젠가 SQL 커서로 갈아엎을 자리라, 그때 <b>무엇을 만족해야 하는지</b>를 여기에 못 박아 둔다 —
 * 페이지 경계에서 누락·중복이 없을 것, 기본 20·최대 50, 못 쓰는 커서는 400 으로 되돌릴 것.
 *
 * <p>즉 이 테스트는 지금 구현이 도는지를 확인하는 동시에, 교체 후에도 그대로 통과해야 하는 계약이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoomThreadPagingIT extends ChallengeApiSupport {

    /** 확정 시각을 과거로 둔다 — DB 와 JVM 시계가 수십 ms 어긋나면 "아직 오지 않은 이벤트"로 걸러진다. */
    private static final String PAST_BASE = "DATE_SUB(NOW(6), INTERVAL ? MINUTE)";

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
    @DisplayName("커서를 따라가면 최신순으로 누락도 중복도 없이 전부 읽힌다")
    void pagesThroughWithoutGapsOrDuplicates() throws Exception {
        Member owner = member(uniq("paging-owner"));
        UUID challengeId = room(owner);
        List<UUID> expected = insertEvents(challengeId, owner.id(), 7);   // 최신 → 과거 순

        List<String> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode data = data(threads(challengeId, owner.token(), cursor, 3));
            for (JsonNode item : data.path("items")) collected.add(item.path("id").asText());
            cursor = data.path("nextCursor").isNull() ? null : data.path("nextCursor").asText();
            pages++;
            assertThat(pages).as("페이지가 끝나지 않으면 커서가 제자리를 돈다는 뜻이다").isLessThan(10);
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);                       // 3 + 3 + 1
        assertThat(collected).doesNotHaveDuplicates();
        assertThat(collected).containsExactlyElementsOf(expected.stream().map(UUID::toString).toList());
    }

    @Test
    @DisplayName("페이지 크기는 기본 20이고 아무리 크게 요청해도 50에서 잘린다")
    void defaultAndMaxPageSize() throws Exception {
        Member owner = member(uniq("paging-size"));
        UUID challengeId = room(owner);
        insertEvents(challengeId, owner.id(), 55);

        assertThat(data(threads(challengeId, owner.token(), null, null)).path("items")).hasSize(20);
        assertThat(data(threads(challengeId, owner.token(), null, 100)).path("items")).hasSize(50);
        assertThat(data(threads(challengeId, owner.token(), null, 5)).path("items")).hasSize(5);
    }

    @Test
    @DisplayName("못 쓰는 커서는 400 CURSOR_INVALID — 클라는 처음부터 다시 부른다")
    void unusableCursorAsksForRestart() throws Exception {
        Member owner = member(uniq("paging-cursor"));
        UUID challengeId = room(owner);
        insertEvents(challengeId, owner.id(), 3);

        // 형식이 깨진 커서
        expectError(threads(challengeId, owner.token(), "not-a-cursor", null), 400, "CURSOR_INVALID");
        // 형식은 맞지만 이 피드에 없는 아이템 — 사라진 아이템을 가리키는 커서도 같은 경로다
        expectError(threads(challengeId, owner.token(), UUID.randomUUID().toString(), null),
                400, "CURSOR_INVALID");
    }

    // ===== 헬퍼 =====

    private UUID room(Member owner) {
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        return challengeId;
    }

    private MvcResult threads(UUID challengeId, String token, String cursor, Integer size) throws Exception {
        StringBuilder url = new StringBuilder("/api/v1/challenges/" + challengeId + "/threads?_=1");
        if (cursor != null) url.append("&cursor=").append(cursor);
        if (size != null) url.append("&size=").append(size);
        return getAuth(url.toString(), token);
    }

    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return OM.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /**
     * 성공 이벤트 n건. 날짜와 확정 시각을 1씩 벌려 정렬이 흔들리지 않게 한다
     * (VerificationDaily 는 멤버·날짜 유니크라 날짜도 함께 밀어야 한다).
     *
     * @return 기대 노출 순서(최신 먼저)의 id 목록
     */
    private List<UUID> insertEvents(UUID challengeId, UUID userId, int count) {
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM challenge_members WHERE challenge_id=? AND user_id=?",
                (rs, i) -> uuidOf(rs.getBytes(1)), bytes(challengeId), bytes(userId));
        List<UUID> newestFirst = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO VerificationDaily " +
                            "(id, challengeMemberId, challengeId, userId, targetDate, status, verifiedAt, shareableAt) " +
                            "VALUES (?, ?, ?, ?, DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY), 'SUCCESS', "
                            + PAST_BASE + ", " + PAST_BASE + ")",
                    bytes(id), bytes(memberId), bytes(challengeId), bytes(userId), i, i + 1, i + 1);
            newestFirst.add(id);   // i가 커질수록 과거 → 그대로가 최신순
        }
        return newestFirst;
    }

    private static UUID uuidOf(byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
