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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 운영자 → 회원 개별 알림 — 회원 검색(최근 이의·신고·문의한 사람이 위) · 보내기 · 권한 · 감사 로그.
 *
 * <p>테스트 DB 는 스위트 전체가 공유한다. 검색은 이 클래스가 만든 고유 닉네임 접두로 걸러 단언한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminUserMessageIT extends ChallengeApiSupport {

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
    @DisplayName("일반 회원은 검색·보내기 모두 403")
    void membersAreForbidden() throws Exception {
        Member normal = member(uniq("umn"));
        assertThat(getAuth("/api/v1/admin/users/search?q=a", normal.token()).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(postJsonAuth("/api/v1/admin/users/" + normal.id() + "/messages", normal.token(),
                Map.of("title", "t", "body", "b")).getResponse().getStatus()).isEqualTo(403);
        assertThat(getAuth("/api/v1/admin/messages", normal.token()).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("검색은 최근 이의·신고·문의한 사람을 위에, 마지막 활동이 최근인 순으로 둔다")
    void searchPutsRecentActorsFirst() throws Exception {
        Member op = operator("umo");
        String prefix = "검" + UUID.randomUUID().toString().substring(0, 6);
        Member quiet = named(prefix + "a");
        Member inquirer = named(prefix + "b");
        Member appellant = named(prefix + "c");
        Member reporter = named(prefix + "d");
        Member old = named(prefix + "e");
        Instant now = Instant.now();

        inquiry(inquirer.id(), now.minus(Duration.ofDays(2)));
        appeal(appellant.id(), now.minus(Duration.ofHours(1)));
        report(reporter.id(), quiet.id(), now.minus(Duration.ofHours(5)));
        appeal(old.id(), now.minus(Duration.ofDays(45)));   // 30일 밖 — 활동 없는 사람과 같다

        MvcResult res = getAuth("/api/v1/admin/users/search?q=" + prefix, op.token());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        List<String> ids = read(res, "$.data.items[*].userId");
        assertThat(ids.subList(0, 3)).containsExactly(
                appellant.id().toString(), reporter.id().toString(), inquirer.id().toString());
        assertThat(ids.subList(3, 5)).containsExactlyInAnyOrder(quiet.id().toString(), old.id().toString());

        assertThat((String) read(res, "$.data.items[0].lastActionType")).isEqualTo("APPEAL");
        assertThat((Integer) read(res, "$.data.items[0].appealCount")).isEqualTo(1);
        assertThat((String) read(res, "$.data.items[1].lastActionType")).isEqualTo("REPORT");
        assertThat((Integer) read(res, "$.data.items[1].reportCount")).isEqualTo(1);
        assertThat((String) read(res, "$.data.items[2].lastActionType")).isEqualTo("INQUIRY");
        assertThat((Integer) read(res, "$.data.items[2].openInquiryCount")).isEqualTo(1);
        assertThat((String) read(res, "$.data.items[2].nickname")).isEqualTo(prefix + "b");
        assertThat((Integer) read(res, "$.data.recentDays")).isEqualTo(30);

        // 검색어가 없으면 최근 활동한 사람만 — 조용한 사람·30일 밖 사람은 빠진다.
        List<String> recent = read(getAuth("/api/v1/admin/users/search?size=100", op.token()),
                "$.data.items[*].userId");
        assertThat(recent).contains(appellant.id().toString(), reporter.id().toString(), inquirer.id().toString())
                .doesNotContain(quiet.id().toString(), old.id().toString());

        // 회원 ID 로도 찾는다.
        List<String> byId = read(getAuth("/api/v1/admin/users/search?q=" + quiet.id(), op.token()),
                "$.data.items[*].userId");
        assertThat(byId).containsExactly(quiet.id().toString());

        // 탈퇴자는 빠진다.
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = NOW(3) WHERE id = ?", bytes(old.id()));
        assertThat((List<String>) read(getAuth("/api/v1/admin/users/search?q=" + prefix, op.token()),
                "$.data.items[*].userId")).doesNotContain(old.id().toString()).hasSize(4);

        Integer audited = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE operator_id = ?"
                + " AND action = 'USER_SEARCH' AND result = 'ALLOWED'", Integer.class, bytes(op.id()));
        assertThat(audited).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("LIKE 와일드카드는 글자 그대로 찾는다")
    void wildcardIsLiteral() throws Exception {
        Member op = operator("umw");
        String prefix = "검" + UUID.randomUUID().toString().substring(0, 6);
        Member m = named(prefix + "z");
        // '_' 가 와일드카드로 먹히면 'z' 와 맞아 걸린다.
        assertThat((List<String>) read(getAuth("/api/v1/admin/users/search?q=" + prefix + "_", op.token()),
                "$.data.items[*].userId")).isEmpty();
        assertThat((List<String>) read(getAuth("/api/v1/admin/users/search?q=" + prefix + "Z", op.token()),
                "$.data.items[*].userId")).as("대소문자는 가리지 않는다").containsExactly(m.id().toString());
    }

    @Test
    @DisplayName("보내면 그 회원 알림함에 운영자 문구 그대로 쌓이고 USER_MESSAGE 로 남는다")
    void sendStoresAuthoredNotification() throws Exception {
        Member op = operator("ums");
        Member who = member(uniq("umt"));

        MvcResult res = postJsonAuth("/api/v1/admin/users/" + who.id() + "/messages", op.token(),
                Map.of("title", "  RuleUp 운영팀이에요 ", "body", "남겨주신 이의 내용 확인했어요."));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String notificationId = read(res, "$.data.notificationId");
        assertThat((String) read(res, "$.data.userId")).isEqualTo(who.id().toString());
        assertThat((String) read(res, "$.data.nickname")).isNotBlank();

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT type, title, body, deeplink FROM notifications"
                + " WHERE user_id = ? AND type = 'OPERATOR_MESSAGE'", bytes(who.id()));
        assertThat(row.get("title")).isEqualTo("RuleUp 운영팀이에요");
        assertThat(row.get("body")).isEqualTo("남겨주신 이의 내용 확인했어요.");
        assertThat(row.get("deeplink")).isNull();

        // 받은 사람의 알림함 API 에 그대로 보인다.
        MvcResult inbox = getAuth("/api/v1/notifications", who.token());
        List<String> inboxIds = read(inbox, "$.data.items[*].id");
        assertThat(inboxIds).contains(notificationId);

        // 같은 내용을 두 번 보내도 두 건이 된다 — 멱등 키가 보낼 때마다 새 값이다.
        postJsonAuth("/api/v1/admin/users/" + who.id() + "/messages", op.token(),
                Map.of("title", "RuleUp 운영팀이에요", "body", "남겨주신 이의 내용 확인했어요."));
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications"
                + " WHERE user_id = ? AND type = 'OPERATOR_MESSAGE'", Integer.class, bytes(who.id()));
        assertThat(count).isEqualTo(2);

        Integer audited = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE operator_id = ?"
                        + " AND action = 'USER_MESSAGE' AND target_type = 'USER' AND target_id = ?"
                        + " AND result = 'ALLOWED' AND payload_digest IS NOT NULL",
                Integer.class, bytes(op.id()), bytes(who.id()));
        assertThat(audited).isEqualTo(2);
    }

    @Test
    @DisplayName("보낸 내역은 최신순이고 회원으로 거르며, 커서로 빠짐없이 넘기고 USER_MESSAGE_LIST_VIEW 로 남는다")
    void sentHistory() throws Exception {
        Member op = operator("umh");
        Member a = member(uniq("uma"));
        Member b = member(uniq("umb2"));
        List<String> sentToA = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            MvcResult r = postJsonAuth("/api/v1/admin/users/" + a.id() + "/messages", op.token(),
                    Map.of("title", "제목" + i, "body", "본문" + i));
            sentToA.add(read(r, "$.data.notificationId"));
        }
        postJsonAuth("/api/v1/admin/users/" + b.id() + "/messages", op.token(), Map.of("title", "다른 회원", "body", "본문"));

        MvcResult res = getAuth("/api/v1/admin/messages?userId=" + a.id(), op.token());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        List<String> ids = read(res, "$.data.items[*].notificationId");
        assertThat(ids).containsExactly(sentToA.get(2), sentToA.get(1), sentToA.get(0));
        assertThat((String) read(res, "$.data.items[0].title")).isEqualTo("제목3");
        assertThat((String) read(res, "$.data.items[0].body")).isEqualTo("본문3");
        assertThat((String) read(res, "$.data.items[0].userId")).isEqualTo(a.id().toString());
        assertThat((String) read(res, "$.data.items[0].nickname")).isNotBlank();
        assertThat((String) read(res, "$.data.items[0].status")).isEqualTo("ACTIVE");
        assertThat((Object) read(res, "$.data.items[0].pushedAt")).as("시험 환경엔 기기가 없다").isNull();
        assertThat((Object) read(res, "$.data.nextCursor")).isNull();

        // 한 장에 한 건씩 넘겨도 세 건을 정확히 한 번씩 본다.
        List<String> paged = new java.util.ArrayList<>();
        String cursor = null;
        do {
            MvcResult page = getAuth("/api/v1/admin/messages?size=1&userId=" + a.id()
                    + (cursor == null ? "" : "&cursor=" + cursor), op.token());
            paged.addAll(read(page, "$.data.items[*].notificationId"));
            cursor = read(page, "$.data.nextCursor");
        } while (cursor != null);
        assertThat(paged).containsExactlyElementsOf(ids);

        // 거르지 않으면 다른 회원 것도 함께, 최신이 먼저.
        List<String> all = read(getAuth("/api/v1/admin/messages?size=100", op.token()), "$.data.items[*].userId");
        assertThat(all).contains(a.id().toString(), b.id().toString());
        assertThat(all.getFirst()).isEqualTo(b.id().toString());

        // 다른 종류의 알림은 섞이지 않는다.
        List<String> types = jdbcTemplate.queryForList("SELECT type FROM notifications WHERE id IN (?, ?, ?)",
                String.class, bytes(UUID.fromString(ids.get(0))), bytes(UUID.fromString(ids.get(1))),
                bytes(UUID.fromString(ids.get(2))));
        assertThat(types).containsOnly("OPERATOR_MESSAGE");

        expectError(getAuth("/api/v1/admin/messages?userId=not-a-uuid", op.token()), 400, "INVALID_REQUEST");
        expectError(getAuth("/api/v1/admin/messages?cursor=%21%21", op.token()), 400, "INVALID_REQUEST");

        Integer audited = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE operator_id = ?"
                + " AND action = 'USER_MESSAGE_LIST_VIEW' AND result = 'ALLOWED'", Integer.class, bytes(op.id()));
        assertThat(audited).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("빈 제목·본문, 길이 초과는 400 · 없는 회원·탈퇴자·운영자는 404")
    void badInput() throws Exception {
        Member op = operator("umb");
        Member who = member(uniq("umu"));
        String url = "/api/v1/admin/users/" + who.id() + "/messages";

        expectError(postJsonAuth(url, op.token(), Map.of("title", " ", "body", "본문")), 400, "INVALID_REQUEST");
        expectError(postJsonAuth(url, op.token(), Map.of("title", "제목", "body", "")), 400, "INVALID_REQUEST");
        expectError(postJsonAuth(url, op.token(), Map.of("title", "가".repeat(51), "body", "본문")),
                400, "INVALID_REQUEST");
        expectError(postJsonAuth(url, op.token(), Map.of("title", "제목", "body", "가".repeat(501))),
                400, "INVALID_REQUEST");

        expectError(postJsonAuth("/api/v1/admin/users/" + UUID.randomUUID() + "/messages", op.token(),
                Map.of("title", "제목", "body", "본문")), 404, "USER_NOT_FOUND");
        expectError(postJsonAuth("/api/v1/admin/users/" + op.id() + "/messages", op.token(),
                Map.of("title", "제목", "body", "본문")), 404, "USER_NOT_FOUND");
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = NOW(3) WHERE id = ?", bytes(who.id()));
        expectError(postJsonAuth(url, op.token(), Map.of("title", "제목", "body", "본문")), 404, "USER_NOT_FOUND");

        Integer stored = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications"
                + " WHERE user_id = ? AND type = 'OPERATOR_MESSAGE'", Integer.class, bytes(who.id()));
        assertThat(stored).isZero();
    }

    // ===== 픽스처 =====

    private Member operator(String tag) throws Exception {
        Member op = member(uniq(tag));
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(op.id()));
        return op;
    }

    /**
     * 닉네임을 고정한 회원 — 공유 DB 에서 이 클래스의 회원만 걸러 내려고 쓴다. 가입 검수가 비동기로 끝나며
     * 승인 닉네임을 신청 닉네임으로 덮으므로, 검수를 기다린 뒤 두 칸을 함께 바꾼다.
     */
    private Member named(String nickname) throws Exception {
        Member m = member(uniq("umm"));
        awaitModeration();
        jdbcTemplate.update("UPDATE users SET nickname = ?, approved_nickname = ? WHERE id = ?",
                nickname, nickname, bytes(m.id()));
        return m;
    }

    private void inquiry(UUID userId, Instant at) {
        jdbcTemplate.update("INSERT INTO inquiries (id, user_id, category, origin_category, body, status, created_at)"
                        + " VALUES (?, ?, 'ERROR_ETC', 'ERROR_ETC', ?, 'RECEIVED', ?)",
                bytes(UUID.randomUUID()), bytes(userId), "앱이 자꾸 꺼져요. 확인 부탁드려요.", DbTime.utc(at));
    }

    private void report(UUID reporterId, UUID targetUserId, Instant at) {
        jdbcTemplate.update("INSERT INTO reports (id, reporter_id, target_type, target_id, reason, created_at)"
                        + " VALUES (?, ?, 'USER', ?, 'SPAM_AD', ?)",
                bytes(UUID.randomUUID()), bytes(reporterId), bytes(targetUserId), DbTime.utc(at));
    }

    private void appeal(UUID userId, Instant acceptedAt) {
        UUID challengeId = insertChallenge(userId, "EXERCISE", "ACTIVE", "GROUP");
        UUID dailyId = UUID.randomUUID();
        LocalDate targetDate = LocalDate.ofInstant(acceptedAt, KST).minusDays(1);
        jdbcTemplate.update("INSERT INTO VerificationDaily (id, challengeMemberId, challengeId, userId, targetDate, status, verifiedVia)"
                        + " VALUES (?, ?, ?, ?, ?, 'SUCCESS', 'APPEAL')",
                bytes(dailyId), bytes(UUID.randomUUID()), bytes(challengeId), bytes(userId), targetDate);
        jdbcTemplate.update("INSERT INTO verification_appeals (id, verificationDailyId, challengeId, challengeMemberId, userId,"
                        + " targetDate, reason, acceptedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                bytes(UUID.randomUUID()), bytes(dailyId), bytes(challengeId), bytes(UUID.randomUUID()), bytes(userId),
                targetDate, "인증이 안 됐는데 문의는 어디로 하나요? 답변 부탁드려요.", DbTime.utc(acceptedAt));
    }
}
