package com.ruleup.ruleup_backend.admin;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.domain.AdminAuditLog;
import com.ruleup.ruleup_backend.admin.repository.AdminAuditLogRepository;
import com.ruleup.ruleup_backend.admin.stats.DailyServiceStatsBatch;
import com.ruleup.ruleup_backend.admin.stats.DailyServiceStatsService;
import com.ruleup.ruleup_backend.admin.stats.DailyServiceStatsService.DailyStat;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.common.DbTime;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 일별 서비스 지표 — 정의·날짜 경계·멱등·관리자 조회.
 *
 * <p>테스트 DB 는 스위트 전체가 공유하므로 <b>다른 시험이 쓰지 않는 과거 날짜</b>(2021년)에 픽스처를
 * 심고, 시험마다 서로 다른 날짜를 쓴다. 다른 시험이 만드는 행은 전부 「지금」 근처라 그 날짜에 섞이지
 * 않는다 — 그래서 전체를 세는 지표도 정확한 값으로 단언할 수 있다.
 *
 * <p>접속은 운영과 같은 serverTimezone=Asia/Seoul 이다. 픽스처 시각은 {@link DbTime#utc} 로 넣어
 * 운영 코드와 같은 UTC 벽시계 규약을 따른다 — 경계 단언이 9시간 어긋나면 이 규약이 깨진 것이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DailyServiceStatsIT extends ChallengeApiSupport {

    /** 관리자 요청이 쓴 테이블·권한이 관리자 DB 계정의 권한 목록 안에 있는지 — 넘으면 grants 를 갱신하라고 알린다. */
    @org.junit.jupiter.api.AfterAll
    static void adminQueriesStayWithinGrants() throws java.io.IOException {
        com.ruleup.ruleup_backend.admin.access.AdminSqlCapture.assertWithinGrants();
    }


    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DailyServiceStatsService statsService;
    @Autowired DailyServiceStatsBatch statsBatch;
    @Autowired AdminAuditLogRepository auditLogRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() { return mvc; }

    @Override
    protected JdbcTemplate jdbc() { return jdbcTemplate; }

    // ===== 픽스처 =====

    /** 하루 D 에 대해 모든 지표가 서로 다른 값이 되도록 심는다. 기대값은 각 단언 옆에 적는다. */
    private void seed(LocalDate day) throws Exception {
        Member a = member(uniq("sa"));   // D 00:00:00.000 KST 가입 — 경계 포함
        Member b = member(uniq("sb"));   // D 23:59:59.999 KST 가입
        Member c = member(uniq("sc"));   // D-1 23:59:59.999 KST 가입 — 전날
        Member d = member(uniq("sd"));   // D+1 00:00 KST 가입 — 다음 날
        Member op = member(uniq("so"));  // D 가입이지만 운영자 — 어디에도 세지 않는다
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(op.id()));

        setCreatedAt(a.id(), kst(day, 0, 0, 0, 0));
        setCreatedAt(b.id(), kst(day, 23, 59, 59, 999));
        setCreatedAt(c.id(), kst(day.minusDays(1), 23, 59, 59, 999));
        setCreatedAt(d.id(), kst(day.plusDays(1), 0, 0, 0, 0));
        setCreatedAt(op.id(), kst(day, 12, 0, 0, 0));

        // 챌린지 2개 — a 가 D 에 개설. 개설자는 가입 사건 없이 방장 멤버 행만 생긴다.
        UUID ch1 = insertChallenge(a.id(), "EXERCISE", "ACTIVE", "GROUP");
        UUID ch2 = insertChallenge(a.id(), "EXERCISE", "ACTIVE", "GROUP");
        jdbcTemplate.update("UPDATE challenges SET created_at = ? WHERE id IN (?, ?)",
                DbTime.utc(kst(day, 10, 0, 0, 0)), bytes(ch1), bytes(ch2));
        insertMember(ch1, a.id(), "OWNER", "ACTIVE", kst(day, 10, 0, 0, 0));
        insertMember(ch2, a.id(), "OWNER", "ACTIVE", kst(day, 10, 0, 0, 1));

        // b: D 에 ch1 첫 가입. 참여 이력은 삭제된 방의 보관 이력에만 있다 → 재참여 유저.
        insertMember(ch1, b.id(), "MEMBER", "ACTIVE", kst(day, 12, 0, 0, 0));
        insertJoinEvent(ch1, b.id(), kst(day, 12, 0, 0, 0));
        jdbcTemplate.update("INSERT INTO challenge_member_history (challenge_id, user_id, final_role, left_type, joined_at)"
                        + " VALUES (?, ?, 'MEMBER', 'LEFT', ?)",
                bytes(UUID.randomUUID()), bytes(b.id()), DbTime.utc(kst(day.minusDays(40), 9, 0, 0, 0)));

        // c: 3월 초 ch2 에 들어왔다 나간 뒤 D 에 재입장 — 같은 방 재입장이자 재참여 유저.
        insertMember(ch2, c.id(), "MEMBER", "ACTIVE", kst(day.minusDays(9), 9, 0, 0, 0));
        insertJoinEvent(ch2, c.id(), kst(day.minusDays(9), 9, 0, 0, 0));
        insertJoinEvent(ch2, c.id(), kst(day, 13, 0, 0, 0));

        // 운영자 가입은 세지 않는다. 다음 날 가입 사건도 D 가 아니다.
        insertMember(ch1, op.id(), "MEMBER", "ACTIVE", kst(day, 14, 0, 0, 0));
        insertJoinEvent(ch1, op.id(), kst(day, 14, 0, 0, 0));
        insertMember(ch1, d.id(), "MEMBER", "ACTIVE", kst(day.plusDays(1), 0, 0, 0, 0));
        insertJoinEvent(ch1, d.id(), kst(day.plusDays(1), 0, 0, 0, 0));

        // 귀속일 D 판정
        insertDaily(a.id(), ch1, day, "SUCCESS", "AUTO", null);           // 시도 · 성공
        insertDaily(b.id(), ch1, day, "SUCCESS", "MANUAL", null);         // 시도 · 성공(수동 체크)
        UUID appealed = insertDaily(c.id(), ch2, day, "SUCCESS", "APPEAL", null); // 성공(이의 인용) · 시도 아님
        insertDaily(a.id(), ch2, day, "FAILED", null, null);              // 시도 · 실패(측정된 미달)
        UUID noSignal = insertDaily(b.id(), ch1, day, "FAILED", null, "NO_SIGNAL"); // 실패 · 증거 없음
        insertDaily(c.id(), ch2, day, "PENDING", null, null);             // 대상 · 미확정
        insertDaily(a.id(), ch1, day, "NOT_TARGET", null, null);          // 분모 아님
        insertDaily(b.id(), ch1, day, "NOT_REQUIRED", null, null);        // 분모 아님
        insertDaily(op.id(), ch1, day, "SUCCESS", "AUTO", null);          // 운영자 — 제외
        insertDaily(a.id(), ch1, day.plusDays(1), "SUCCESS", "AUTO", null); // 다음 귀속일

        // 이의 — D 00:00 KST 정각 접수는 D, D-1 23:59:59.999 KST 접수는 전날
        insertAppeal(appealed, ch2, c.id(), day, kst(day, 0, 0, 0, 0));
        insertAppeal(noSignal, ch1, b.id(), day, kst(day.minusDays(1), 23, 59, 59, 999));
    }

    private static Instant kst(LocalDate day, int h, int m, int s, int ms) {
        return day.atTime(h, m, s, ms * 1_000_000).atZone(KST).toInstant();
    }

    private void setCreatedAt(UUID userId, Instant at) {
        jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?", DbTime.utc(at), bytes(userId));
    }

    private void insertMember(UUID challengeId, UUID userId, String role, String status, Instant joinedAt) {
        jdbcTemplate.update("INSERT INTO challenge_members (id, challenge_id, user_id, role, status, joined_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                bytes(UUID.randomUUID()), bytes(challengeId), bytes(userId), role, status, DbTime.utc(joinedAt));
    }

    private void insertJoinEvent(UUID challengeId, UUID userId, Instant joinedAt) {
        jdbcTemplate.update("INSERT INTO challenge_join_events (id, challenge_id, user_id, joined_at) VALUES (?, ?, ?, ?)",
                bytes(UUID.randomUUID()), bytes(challengeId), bytes(userId), DbTime.utc(joinedAt));
    }

    private UUID insertDaily(UUID userId, UUID challengeId, LocalDate targetDate, String status,
                             String verifiedVia, String gapReason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO VerificationDaily (id, challengeMemberId, challengeId, userId, targetDate,"
                        + " status, verifiedVia, gapReason) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                bytes(id), bytes(UUID.randomUUID()), bytes(challengeId), bytes(userId), targetDate,
                status, verifiedVia, gapReason);
        return id;
    }

    private void insertAppeal(UUID dailyId, UUID challengeId, UUID userId, LocalDate targetDate, Instant acceptedAt) {
        jdbcTemplate.update("INSERT INTO verification_appeals (id, verificationDailyId, challengeId, challengeMemberId,"
                        + " userId, targetDate, reason, acceptedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                bytes(UUID.randomUUID()), bytes(dailyId), bytes(challengeId), bytes(UUID.randomUUID()),
                bytes(userId), targetDate, "신호가 늦게 올라갔습니다 확인 부탁드립니다", DbTime.utc(acceptedAt));
    }

    private Member operator(String tag) throws Exception {
        Member m = member(uniq(tag));
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(m.id()));
        return m;
    }

    /** 판정이 확정되는 시각 — 귀속일 D+2 00:00 KST. */
    private static Instant finalizeAt(LocalDate day) {
        return day.plusDays(2).atStartOfDay(KST).toInstant();
    }

    // ===== 정의 =====

    @Test
    @DisplayName("지표 정의 — KST 하루 경계를 UTC 로 옮겨 세고, 운영자·다른 날·분모 밖 판정은 빠진다")
    void definitions() throws Exception {
        LocalDate day = LocalDate.of(2021, 3, 10);
        seed(day);

        DailyStat s = statsService.compute(day, finalizeAt(day));

        assertThat(s.signups()).as("D 00:00:00.000 과 23:59:59.999 KST 만 — 전날·다음 날·운영자 제외").isEqualTo(2);
        assertThat(s.challengesCreated()).isEqualTo(2);
        assertThat(s.challengeJoins()).as("b 첫 가입 + c 재입장 — 운영자·다음 날·방장 개설 제외").isEqualTo(2);
        assertThat(s.rejoins()).as("같은 방에 이전 가입 사건이 있던 c 만").isEqualTo(1);
        assertThat(s.participants()).as("a(개설) · b(가입) · c(재입장)").isEqualTo(3);
        assertThat(s.returningParticipants()).as("c(현재 멤버 행) · b(삭제된 방 보관 이력) — a 는 첫 참여").isEqualTo(2);

        assertThat(s.verificationTargets()).as("NOT_TARGET·NOT_REQUIRED·운영자·다음 귀속일 제외").isEqualTo(6);
        assertThat(s.verificationAttempts()).as("자동 성공 + 수동 성공 + 측정된 미달 실패").isEqualTo(3);
        assertThat(s.judgedSuccess()).isEqualTo(3);
        assertThat(s.judgedSuccessAppeal()).isEqualTo(1);
        assertThat(s.judgedFail()).isEqualTo(2);
        assertThat(s.judgedFailNoEvidence()).isEqualTo(1);
        assertThat(s.judgementPending()).isEqualTo(1);
        assertThat(s.appeals()).as("D 00:00 KST 정각 접수만 — 전날 23:59:59.999 는 전날").isEqualTo(1);

        assertThat(s.judgementFinal()).isTrue();
        assertThat(s.successRate()).as("판정 결과로만 — 3 / (3 + 2)").isEqualTo(0.6);

        DailyStat prev = statsService.compute(day.minusDays(1), finalizeAt(day));
        assertThat(prev.signups()).as("경계 1ms 전 가입은 전날이다").isEqualTo(1);
        assertThat(prev.appeals()).isEqualTo(1);
    }

    @Test
    @DisplayName("확정 시각(D+2 00:00 KST) 전에 계산한 판정은 잠정값이라 성공률을 내지 않는다")
    void provisionalBeforeFinalization() throws Exception {
        LocalDate day = LocalDate.of(2021, 4, 10);
        seed(day);

        DailyStat early = statsService.compute(day, finalizeAt(day).minusMillis(1));
        assertThat(early.judgementFinal()).isFalse();
        assertThat(early.successRate()).isNull();
        assertThat(early.judgedSuccess()).as("건수는 그대로 남긴다 — 다시 계산할 때 덮어쓴다").isEqualTo(3);
    }

    // ===== 멱등 =====

    @Test
    @DisplayName("같은 날짜를 다시 계산하면 한 행을 덮어쓴다 — 늦게 확정된 판정이 반영된다")
    void recomputeOverwrites() throws Exception {
        LocalDate day = LocalDate.of(2021, 5, 10);
        seed(day);

        statsService.recompute(day, finalizeAt(day).minusSeconds(60));
        statsService.recompute(day, finalizeAt(day).minusSeconds(60));
        assertThat(statsService.find(day, day)).hasSize(1)
                .first().satisfies(s -> assertThat(s.judgementFinal()).isFalse());

        // 확정 배치가 미확정 건을 측정된 실패로 닫았다
        jdbcTemplate.update("UPDATE VerificationDaily SET status = 'FAILED' WHERE targetDate = ? AND status = 'PENDING'", day);
        statsService.recompute(day, finalizeAt(day).plusSeconds(60));

        List<DailyStat> rows = statsService.find(day, day);
        assertThat(rows).hasSize(1);
        DailyStat s = rows.getFirst();
        assertThat(s.judgementFinal()).isTrue();
        assertThat(s.judgementPending()).isZero();
        assertThat(s.judgedFail()).isEqualTo(3);
        assertThat(s.verificationAttempts()).isEqualTo(4);
        assertThat(s.successRate()).isEqualTo(0.5);
        assertThat(s.computedAt()).isEqualTo(finalizeAt(day).plusSeconds(60));
    }

    @Test
    @DisplayName("배치는 00:40 KST 기준 어제·그저께·사흘 전을 계산하고, 그저께부터 판정이 확정값이다")
    void batchComputesRecentThreeDays() throws Exception {
        LocalDate day = LocalDate.of(2021, 6, 10);
        seed(day);
        Instant now = day.plusDays(2).atTime(0, 40).atZone(KST).toInstant();   // D+2 00:40 KST

        List<LocalDate> done = statsBatch.aggregateRecentDays(now);

        assertThat(done).containsExactly(day.minusDays(1), day, day.plusDays(1));
        List<DailyStat> rows = statsService.find(day.minusDays(1), day.plusDays(1));
        assertThat(rows).extracting(DailyStat::statDate).containsExactly(day.minusDays(1), day, day.plusDays(1));
        assertThat(rows.get(1).judgementFinal()).as("그저께(D) — 00:00 에 확정됐다").isTrue();
        assertThat(rows.get(1).signups()).isEqualTo(2);
        assertThat(rows.get(2).judgementFinal()).as("어제(D+1) — 아직 유예 중").isFalse();
        assertThat(rows.get(2).signups()).isEqualTo(1);
    }

    // ===== 관리자 조회 =====

    @Test
    @DisplayName("관리자 조회·CSV·재계산 — 운영자만, 전부 감사 로그에 남는다")
    void adminEndpoints() throws Exception {
        LocalDate day = LocalDate.of(2021, 7, 10);
        seed(day);
        Member op = operator("statop");
        String range = "from=" + day.minusDays(1) + "&to=" + day;

        // 재계산(백필)
        MvcResult recomputed = mvc.perform(post("/api/v1/admin/stats/daily/recompute")
                .header("Authorization", "Bearer " + op.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("from", day.minusDays(1).toString(), "to", day.toString()))))
                .andReturn();
        assertThat(recomputed.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) read(recomputed, "$.data.recomputedDays")).isEqualTo(2);

        // JSON 목록
        MvcResult list = getAuth("/api/v1/admin/stats/daily?" + range, op.token());
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat((List<?>) read(list, "$.data.items")).hasSize(2);
        assertThat((String) read(list, "$.data.items[1].statDate")).isEqualTo(day.toString());
        assertThat((Integer) read(list, "$.data.items[1].signups")).isEqualTo(2);
        assertThat((Integer) read(list, "$.data.items[1].verificationAttempts")).isEqualTo(3);
        assertThat((Double) read(list, "$.data.items[1].successRate")).isEqualTo(0.6);

        // CSV
        MvcResult csv = getAuth("/api/v1/admin/stats/daily/csv?" + range, op.token());
        assertThat(csv.getResponse().getStatus()).isEqualTo(200);
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getHeader("Content-Disposition"))
                .contains("attachment").contains("daily-service-stats_" + day.minusDays(1) + "_" + day + ".csv");
        String body = new String(csv.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String[] lines = body.replace("﻿", "").split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).startsWith("stat_date,signups,challenges_created,challenge_joins,rejoins,");
        assertThat(lines[2]).startsWith(day + ",2,2,2,1,3,2,6,3,3,1,2,1,1,true,0.6,1,");

        assertThat(auditLogRepository.findByOperatorIdOrderByOccurredAtDesc(op.id()))
                .extracting(AdminAuditLog::getAction)
                .contains(AdminAction.STATS_RECOMPUTE, AdminAction.STATS_VIEW, AdminAction.STATS_EXPORT);
    }

    @Test
    @DisplayName("일반 회원은 403, 잘못된 기간·오늘 이후 재계산은 400")
    void adminGuards() throws Exception {
        Member normal = member(uniq("statnormal"));
        assertThat(getAuth("/api/v1/admin/stats/daily", normal.token()).getResponse().getStatus()).isEqualTo(403);
        assertThat(getAuth("/api/v1/admin/stats/daily/csv", normal.token()).getResponse().getStatus()).isEqualTo(403);

        Member op = operator("statguard");
        assertThat(getAuth("/api/v1/admin/stats/daily?from=2021-03-10&to=2021-03-01", op.token())
                .getResponse().getStatus()).as("끝이 시작보다 앞").isEqualTo(400);
        assertThat(getAuth("/api/v1/admin/stats/daily?from=2020-01-01&to=2021-03-01", op.token())
                .getResponse().getStatus()).as("366일 초과").isEqualTo(400);
        assertThat(getAuth("/api/v1/admin/stats/daily?from=2021-13-01", op.token())
                .getResponse().getStatus()).as("날짜 형식").isEqualTo(400);

        String today = LocalDate.now(KST).toString();
        MvcResult future = mvc.perform(post("/api/v1/admin/stats/daily/recompute")
                .header("Authorization", "Bearer " + op.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("from", today, "to", today))))
                .andReturn();
        assertThat(future.getResponse().getStatus()).as("끝나지 않은 하루는 저장하지 않는다").isEqualTo(400);

        // 기본 기간(어제까지 30일)은 그대로 통과한다
        assertThat(getAuth("/api/v1/admin/stats/daily", op.token()).getResponse().getStatus()).isEqualTo(200);
    }
}
