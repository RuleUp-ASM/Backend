package com.ruleup.ruleup_backend.admin;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.common.DbTime;
import org.junit.jupiter.api.AfterAll;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 관리자 이의 내역 — Backend #361. 목록(최신순·필터·커서) · 상세(원래 실패 설명·사진·최근 30일 수) · 권한 · 감사 로그.
 *
 * <p>테스트 DB 는 스위트 전체가 공유한다. 목록은 이 클래스가 만든 회원으로 걸러 단언한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminAppealIT extends ChallengeApiSupport {

    /** 관리자 요청이 쓴 테이블·권한이 관리자 DB 계정의 권한 목록 안에 있는지. */
    @AfterAll
    static void adminQueriesStayWithinGrants() throws java.io.IOException {
        com.ruleup.ruleup_backend.admin.access.AdminSqlCapture.assertWithinGrants();
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() { return mvc; }

    @Override
    protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @Test
    @DisplayName("일반 회원은 목록·상세 모두 403")
    void membersAreForbidden() throws Exception {
        Member normal = member(uniq("apn"));
        assertThat(getAuth("/api/v1/admin/appeals", normal.token()).getResponse().getStatus()).isEqualTo(403);
        assertThat(getAuth("/api/v1/admin/appeals/" + UUID.randomUUID(), normal.token()).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("목록은 최신순이고 회원·방·사진·기간으로 거르며, 커서로 끝까지 빠짐없이 넘긴다")
    void listFiltersAndPages() throws Exception {
        Member op = operator("apo");
        Member who = member(uniq("apu"));
        UUID roomA = insertChallenge(who.id(), "EXERCISE", "ACTIVE", "GROUP");
        UUID roomB = insertChallenge(who.id(), "STUDY", "ACTIVE", "GROUP");
        Instant now = Instant.now();
        // 같은 시각 두 건 — 커서가 (시각, id) 로 이어받지 않으면 하나를 건너뛴다.
        UUID oldest = appeal(who.id(), roomA, now.minus(Duration.ofDays(3)), null, "INSUFFICIENT_DWELL");
        UUID tieA = appeal(who.id(), roomA, now.minus(Duration.ofHours(2)), "/files/a.jpg", "INSUFFICIENT_STEPS");
        UUID tieB = appeal(who.id(), roomB, now.minus(Duration.ofHours(2)), null, null);
        UUID newest = appeal(who.id(), roomB, now.minus(Duration.ofMinutes(5)), null, "WOKE_UP_LATE");

        String base = "/api/v1/admin/appeals?userId=" + who.id();
        MvcResult all = getAuth(base, op.token());
        assertThat(all.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) read(all, "$.data.totalCount")).isEqualTo(4);
        List<String> ids = read(all, "$.data.items[*].appealId");
        assertThat(ids.getFirst()).isEqualTo(newest.toString());
        assertThat(ids.getLast()).isEqualTo(oldest.toString());
        assertThat(ids.subList(1, 3)).containsExactlyInAnyOrder(tieA.toString(), tieB.toString());
        assertThat((String) read(all, "$.data.items[0].failureReason")).isEqualTo("WOKE_UP_LATE");
        assertThat((String) read(all, "$.data.items[0].nickname")).isNotBlank();
        assertThat((String) read(all, "$.data.items[0].challengeCategory")).isEqualTo("STUDY");

        // 한 장에 한 건씩 넘겨도 네 건을 정확히 한 번씩 본다.
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            MvcResult page = getAuth(base + "&size=1" + (cursor == null ? "" : "&cursor=" + enc(cursor)), op.token());
            paged.addAll(read(page, "$.data.items[*].appealId"));
            cursor = read(page, "$.data.nextCursor");
        } while (cursor != null);
        assertThat(paged).containsExactlyElementsOf(ids);

        assertThat(idsOf(getAuth(base + "&challengeId=" + roomA, op.token())))
                .containsExactlyInAnyOrder(oldest.toString(), tieA.toString());
        assertThat(idsOf(getAuth(base + "&hasImage=true", op.token()))).containsExactly(tieA.toString());
        assertThat(idsOf(getAuth(base + "&hasImage=false", op.token()))).hasSize(3);

        LocalDate today = LocalDate.now(KST);
        String twoDaysAgo = today.minusDays(2).toString();
        assertThat(idsOf(getAuth(base + "&from=" + twoDaysAgo + "&to=" + today, op.token())))
                .as("사흘 전 건은 기간 밖").doesNotContain(oldest.toString()).hasSize(3);

        Integer audited = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE operator_id = ?"
                + " AND action = 'APPEAL_QUEUE_VIEW' AND result = 'ALLOWED'", Integer.class, bytes(op.id()));
        assertThat(audited).isGreaterThan(0);
    }

    @Test
    @DisplayName("상세는 사유 전문·원래 실패 설명·사진·최근 30일 이의 수를 내리고 APPEAL_VIEW 로 남는다")
    void detailShowsTheOriginalFailure() throws Exception {
        Member op = operator("apd");
        Member who = member(uniq("apw"));
        UUID room = insertChallenge(who.id(), "EXERCISE", "ACTIVE", "GROUP");
        Instant now = Instant.now();
        appeal(who.id(), room, now.minus(Duration.ofDays(40)), null, "INSUFFICIENT_DWELL");   // 30일 밖
        appeal(who.id(), room, now.minus(Duration.ofDays(10)), null, null);
        UUID target = appeal(who.id(), room, now.minus(Duration.ofHours(1)), "/files/proof.jpg", "INSUFFICIENT_DWELL");

        MvcResult res = getAuth("/api/v1/admin/appeals/" + target, op.token());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) read(res, "$.data.reason")).startsWith("지하철 구간에서 GPS가 끊겼어요");
        assertThat((String) read(res, "$.data.failure.reasonCode")).isEqualTo("INSUFFICIENT_DWELL");
        assertThat((String) read(res, "$.data.failure.summary")).isEqualTo("체류 10분, 목표 30분");
        assertThat((Integer) read(res, "$.data.failure.expected.goalMinutes")).isEqualTo(30);
        assertThat((Integer) read(res, "$.data.failure.actual.dwellMinutes")).isEqualTo(10);
        assertThat((Integer) read(res, "$.data.recentAppealCount30d")).as("40일 전 건은 빠진다").isEqualTo(2);
        assertThat((List<String>) read(res, "$.data.imageUrls")).hasSize(1);
        assertThat((String) read(res, "$.data.imageUrlsExpireAt")).isNotBlank();
        assertThat((String) read(res, "$.data.accountStatus")).isEqualTo("ACTIVE");

        Integer audited = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE operator_id = ?"
                        + " AND action = 'APPEAL_VIEW' AND target_type = 'APPEAL' AND target_id = ? AND result = 'ALLOWED'",
                Integer.class, bytes(op.id()), bytes(target));
        assertThat(audited).isEqualTo(1);
    }

    @Test
    @DisplayName("실패 설명이 없는 옛 접수는 failure 가 null 이다")
    void legacyAppealHasNoFailure() throws Exception {
        Member op = operator("apl");
        Member who = member(uniq("apx"));
        UUID room = insertChallenge(who.id(), "EXERCISE", "ACTIVE", "GROUP");
        UUID legacy = appeal(who.id(), room, Instant.now(), null, null);

        MvcResult res = getAuth("/api/v1/admin/appeals/" + legacy, op.token());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat((Object) read(res, "$.data.failure")).isNull();
        assertThat((List<String>) read(res, "$.data.imageUrls")).isEmpty();
    }

    @Test
    @DisplayName("잘못된 입력은 400, 없는 이의는 404")
    void badInput() throws Exception {
        Member op = operator("apb");
        expectError(getAuth("/api/v1/admin/appeals?from=2026-13-01", op.token()), 400, "INVALID_REQUEST");
        expectError(getAuth("/api/v1/admin/appeals?from=2026-10-02&to=2026-10-01", op.token()), 400, "INVALID_REQUEST");
        expectError(getAuth("/api/v1/admin/appeals?userId=not-a-uuid", op.token()), 400, "INVALID_REQUEST");
        expectError(getAuth("/api/v1/admin/appeals?cursor=%21%21", op.token()), 400, "INVALID_REQUEST");
        expectError(getAuth("/api/v1/admin/appeals/" + UUID.randomUUID(), op.token()), 404, "APPEAL_NOT_FOUND");
    }

    // ===== 픽스처 =====

    private Member operator(String tag) throws Exception {
        Member op = member(uniq(tag));
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(op.id()));
        return op;
    }

    /** 인용된 이의 한 건. reasonCode 가 null 이면 V21 이전 접수처럼 실패 설명이 없다. */
    private UUID appeal(UUID userId, UUID challengeId, Instant acceptedAt, String imageUrl, String reasonCode) {
        UUID dailyId = UUID.randomUUID();
        LocalDate targetDate = LocalDate.ofInstant(acceptedAt, KST).minusDays(1);
        jdbcTemplate.update("INSERT INTO VerificationDaily (id, challengeMemberId, challengeId, userId, targetDate, status, verifiedVia)"
                        + " VALUES (?, ?, ?, ?, ?, 'SUCCESS', 'APPEAL')",
                bytes(dailyId), bytes(UUID.randomUUID()), bytes(challengeId), bytes(userId), targetDate);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO verification_appeals (id, verificationDailyId, challengeId, challengeMemberId, userId,"
                        + " targetDate, reason, imageUrl, acceptedAt, failureReason, failureSummary, failureExpected, failureActual)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                bytes(id), bytes(dailyId), bytes(challengeId), bytes(UUID.randomUUID()), bytes(userId), targetDate,
                "지하철 구간에서 GPS가 끊겼어요. 실제로는 한 시간 있었습니다.", imageUrl, DbTime.utc(acceptedAt),
                reasonCode,
                reasonCode == null ? null : "체류 10분, 목표 30분",
                reasonCode == null ? null : "{\"goalMinutes\":30}",
                reasonCode == null ? null : "{\"dwellMinutes\":10}");
        return id;
    }

    private List<String> idsOf(MvcResult res) throws Exception {
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return read(res, "$.data.items[*].appealId");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
