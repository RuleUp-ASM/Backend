package com.ruleup.ruleup_backend.admin;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.admin.dto.AdminDtos.MemberProfile;
import com.ruleup.ruleup_backend.admin.stats.MemberProfileService;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.common.DbTime;
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

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 회원 구성 집계 — 나이 경계·성별·관심사·동의·활동·참여·탈퇴, 범위(운영자·탈퇴·제외 계정), 관리자 조회.
 *
 * <p>테스트 DB 는 스위트 전체가 공유해 다른 시험이 만든 회원이 섞여 있다. 그래서 절댓값이 아니라 <b>심기 전·후의 차이</b>로
 * 단언한다 — 이 클래스가 심은 회원만큼만 움직여야 한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MemberProfileIT extends ChallengeApiSupport {

    /** 관리자 요청이 쓴 테이블·권한이 관리자 DB 계정의 권한 목록 안에 있는지 — 넘으면 grants 를 갱신하라고 알린다. */
    @org.junit.jupiter.api.AfterAll
    static void adminQueriesStayWithinGrants() throws java.io.IOException {
        com.ruleup.ruleup_backend.admin.access.AdminSqlCapture.assertWithinGrants();
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MemberProfileService profileService;
    @Autowired Clock clock;

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
    @DisplayName("심은 회원만큼만 칸이 움직인다 — 만 나이 경계, 미입력, 운영자·탈퇴자 제외, 관심사·동의·활동·참여·탈퇴 시점")
    void countsSeededMembers() throws Exception {
        MemberProfile before = profileService.snapshot();
        LocalDate today = LocalDate.now(clock.withZone(KST));

        // 가입 픽스처 기본값: 생일 2000-05-27 · MALE · 관심사 EXERCISE·READING · 마케팅 동의 · 이벤트 미동의
        Member a = member(uniq("mpa"));   // 17세 FEMALE, 관심사 WAKE_SLEEP 하나, 마케팅 철회, 이틀 전 활동
        Member b = member(uniq("mpb"));   // 내일 35세가 되는 34세 MALE, 관심사 3개, 방장
        Member c = member(uniq("mpc"));   // 오늘 35세가 된 성별 미입력, 인증 성공 1회
        Member d = member(uniq("mpd"));   // 생일 미입력(옛 계정) MALE
        Member op = member(uniq("mpo"));  // 운영자 — 회원이 아니다
        Member w = member(uniq("mpw"));   // 가입 30분 뒤 탈퇴 — 회원이 아니고 탈퇴 시점에만 센다

        setInfo(a.id(), today.minusYears(17), "FEMALE");
        setInfo(b.id(), today.minusYears(35).plusDays(1), "MALE");
        setInfo(c.id(), today.minusYears(35), null);
        setInfo(d.id(), null, "MALE");
        setInterests(a.id(), "WAKE_SLEEP");
        setInterests(b.id(), "EXERCISE", "STUDY", "MIND");
        jdbcTemplate.update("UPDATE user_agreement_states SET agreed = 0 WHERE user_id = ? AND agreement_type = 'MARKETING'",
                bytes(a.id()));
        jdbcTemplate.update("UPDATE users SET last_active_at = ? WHERE id = ?",
                DbTime.utc(clock.instant().minus(Duration.ofDays(2))), bytes(a.id()));
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(op.id()));
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = created_at + INTERVAL 30 MINUTE WHERE id = ?",
                bytes(w.id()));

        UUID room = insertChallenge(b.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(room, b.id(), "OWNER");
        jdbcTemplate.update("INSERT INTO VerificationDaily (id, challengeMemberId, challengeId, userId, targetDate, status)"
                        + " VALUES (?, ?, ?, ?, ?, 'SUCCESS')",
                bytes(UUID.randomUUID()), bytes(UUID.randomUUID()), bytes(room), bytes(c.id()), today.minusDays(3));

        MemberProfile after = profileService.snapshot();

        assertThat(after.members() - before.members()).as("회원 4명 — 운영자·탈퇴자 제외").isEqualTo(4);
        assertThat(diff(before.ageDecades(), after.ageDecades()))
                .isEqualTo(Map.of("10", 1L, "30", 2L, "UNKNOWN", 1L));
        assertThat(diff(before.ageFiveYear(), after.ageFiveYear()))
                .as("내일 35세는 30-34, 오늘 35세는 35-39").isEqualTo(Map.of("14-19", 1L, "30-34", 1L, "35-39", 1L, "UNKNOWN", 1L));
        assertThat(after.age().unknown() - before.age().unknown()).isEqualTo(1);
        assertThat(diff(before.genders(), after.genders())).isEqualTo(Map.of("FEMALE", 1L, "MALE", 2L, "UNKNOWN", 1L));

        AdminDtos.AgeGender thirties0 = band(before, "30");
        AdminDtos.AgeGender thirties1 = band(after, "30");
        assertThat(thirties1.male() - thirties0.male()).isEqualTo(1);
        assertThat(thirties1.undisclosed() - thirties0.undisclosed()).as("성별 미입력은 밝히지 않음에 든다").isEqualTo(1);

        assertThat(diff(before.providers(), after.providers()).values().stream().mapToLong(Long::longValue).sum()).isEqualTo(4);
        assertThat(diff(before.interests(), after.interests()))
                .isEqualTo(Map.of("WAKE_SLEEP", 1L, "EXERCISE", 3L, "STUDY", 1L, "MIND", 1L, "READING", 2L));
        assertThat(diff(before.interestsPerMember(), after.interestsPerMember())).isEqualTo(Map.of("1", 1L, "2", 2L, "3", 1L));

        AdminDtos.Consent marketing0 = consent(before, "MARKETING");
        AdminDtos.Consent marketing1 = consent(after, "MARKETING");
        assertThat(marketing1.agreed() - marketing0.agreed()).isEqualTo(3);
        assertThat(marketing1.notAgreed() - marketing0.notAgreed()).isEqualTo(1);

        assertThat(after.activity().last1d() - before.activity().last1d()).isEqualTo(3);
        assertThat(after.activity().last3d() - before.activity().last3d()).isEqualTo(4);

        assertThat(after.engagement().joinedRoom() - before.engagement().joinedRoom()).isEqualTo(1);
        assertThat(after.engagement().ownedRoom() - before.engagement().ownedRoom()).isEqualTo(1);
        assertThat(after.engagement().succeeded() - before.engagement().succeeded()).isEqualTo(1);
        assertThat(after.withdrawals().within1h() - before.withdrawals().within1h()).isEqualTo(1);

        assertThat(after.ageDecades()).extracting(AdminDtos.Bucket::key)
                .as("고정 칸은 0 이어도 같은 순서로").containsExactlyElementsOf(MemberProfileService.DECADES);
        assertThat(after.interests()).hasSize(12);

        // 제외 계정 — 개발자 시험 계정처럼 지정한 회원은 어디에도 세지 않는다
        MemberProfile excluded = new MemberProfileService(jdbcTemplate, clock, " " + b.id() + " ,").snapshot();
        assertThat(after.members() - excluded.members()).isEqualTo(1);
        assertThat(after.engagement().ownedRoom() - excluded.engagement().ownedRoom()).isEqualTo(1);
    }

    @Test
    @DisplayName("관리자만 조회하고 조회는 감사 로그에 남는다 — 일반 회원은 403")
    void adminEndpoint() throws Exception {
        Member normal = member(uniq("mpn"));
        assertThat(getAuth("/api/v1/admin/dashboard/members", normal.token()).getResponse().getStatus()).isEqualTo(403);

        Member op = member(uniq("mpop"));
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(op.id()));
        MvcResult res = getAuth("/api/v1/admin/dashboard/members", op.token());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat((Object) read(res, "$.data.ageDecades[0].key")).isEqualTo("10");
        assertThat((Object) read(res, "$.data.consents[3].type")).isEqualTo("MARKETING");

        Integer audited = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE operator_id = ?"
                + " AND action = 'DASHBOARD_VIEW' AND result = 'ALLOWED'", Integer.class, bytes(op.id()));
        assertThat(audited).isEqualTo(1);
    }

    @Test
    @DisplayName("참여 중인 방이 있는 사람을 경로로 가른다 — 만든 방만·들어간 방만·둘 다, 끝난 방·나간 방은 빼고 사람 단위로")
    void roomParticipationByPath() throws Exception {
        AdminDtos.RoomParticipation before = profileService.snapshot().participation();

        Member a = member(uniq("rpa"));   // 만든 방 1 + 들어간 방 1 → 둘 다
        Member b = member(uniq("rpb"));   // 만든 방 1 → 만든 방만
        Member c = member(uniq("rpc"));   // 들어간 방 2 → 들어간 방만
        Member d = member(uniq("rpd"));   // 끝난 방 + 나간 방뿐 → 참여 중 아님
        UUID roomA = createdRoom(a.id(), "ACTIVE");
        UUID roomB = createdRoom(b.id(), "UPCOMING");
        insertActiveMembership(roomB, a.id(), "MEMBER");
        insertActiveMembership(roomA, c.id(), "MEMBER");
        insertActiveMembership(roomB, c.id(), "MEMBER");
        insertActiveMembership(createdRoom(b.id(), "COMPLETED"), d.id(), "MEMBER");
        insertActiveMembership(roomA, d.id(), "MEMBER");
        jdbcTemplate.update("UPDATE challenge_members SET status = 'LEFT' WHERE challenge_id = ? AND user_id = ?",
                bytes(roomA), bytes(d.id()));

        AdminDtos.RoomParticipation after = profileService.snapshot().participation();

        assertThat(after.participating() - before.participating()).as("방 수가 아니라 사람 수 — d 는 빠진다").isEqualTo(3);
        assertThat(after.createdOnly() - before.createdOnly()).isEqualTo(1);
        assertThat(after.joinedOnly() - before.joinedOnly()).isEqualTo(1);
        assertThat(after.both() - before.both()).isEqualTo(1);
        assertThat(diff(before.roomsPerMember(), after.roomsPerMember()))
                .as("b 는 끝난 방을 빼고 1개").isEqualTo(Map.of("1", 1L, "2", 2L));
        assertThat(after.roomsPerMember()).extracting(AdminDtos.Bucket::key)
                .containsExactlyElementsOf(MemberProfileService.ROOM_COUNTS);
    }

    @Test
    @DisplayName("참여 방 수 순위 — 같은 개수는 같은 등수, 만든 방·들어간 방을 나눠 보이고 관리자 조회는 회원 조회로 남는다")
    void roomRanking() throws Exception {
        Member top = member(uniq("rkt"));
        Member x = member(uniq("rkx"));
        Member y = member(uniq("rky"));
        Member host = member(uniq("rkh"));
        for (int i = 0; i < 7; i++) createdRoom(top.id(), "ACTIVE");
        for (int i = 0; i < 5; i++) insertActiveMembership(createdRoom(host.id(), "ACTIVE"), top.id(), "MEMBER");
        UUID shared = createdRoom(x.id(), "ACTIVE");
        insertActiveMembership(shared, y.id(), "MEMBER");
        insertActiveMembership(createdRoom(host.id(), "ACTIVE"), x.id(), "MEMBER");
        insertActiveMembership(createdRoom(host.id(), "ACTIVE"), y.id(), "MEMBER");

        List<AdminDtos.RoomRank> ranking = profileService.roomRanking(100);
        AdminDtos.RoomRank t = rank(ranking, top.id());
        AdminDtos.RoomRank rx = rank(ranking, x.id());
        AdminDtos.RoomRank ry = rank(ranking, y.id());

        assertThat(t.rooms()).isEqualTo(12);
        assertThat(t.created()).isEqualTo(7);
        assertThat(t.joined()).isEqualTo(5);
        assertThat(t.nickname()).isNotBlank();
        assertThat(t.rank()).isLessThan(rx.rank());
        assertThat(rx.rank()).as("2개끼리 같은 등수").isEqualTo(ry.rank());
        assertThat(ranking.indexOf(rx)).as("같은 등수 안에서는 만든 방이 많은 쪽이 먼저").isLessThan(ranking.indexOf(ry));

        Member op = member(uniq("rkop"));
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(op.id()));
        assertThat(getAuth("/api/v1/admin/dashboard/members/room-ranking?limit=101", op.token()).getResponse().getStatus())
                .isEqualTo(400);
        MvcResult res = getAuth("/api/v1/admin/dashboard/members/room-ranking?limit=1", op.token());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat((Object) read(res, "$.data[0].rank")).isEqualTo(1);
        Integer audited = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE operator_id = ?"
                + " AND action = 'USER_VIEW' AND result = 'ALLOWED'", Integer.class, bytes(op.id()));
        assertThat(audited).isEqualTo(1);
    }

    // ===== 내부 =====

    /** 방을 만든 것처럼 — 방장 멤버십만 두고 가입 사건은 남기지 않는다(방 생성 경로가 그렇다). */
    private UUID createdRoom(UUID ownerId, String status) {
        UUID room = insertChallenge(ownerId, "EXERCISE", status, "GROUP");
        jdbcTemplate.update("INSERT INTO challenge_members (id, challenge_id, user_id, role, status) VALUES (?, ?, ?, 'OWNER', 'ACTIVE')",
                bytes(UUID.randomUUID()), bytes(room), bytes(ownerId));
        return room;
    }

    private static AdminDtos.RoomRank rank(List<AdminDtos.RoomRank> ranking, UUID userId) {
        return ranking.stream().filter(r -> r.userId().equals(userId.toString())).findFirst().orElseThrow();
    }

    private void setInfo(UUID userId, LocalDate birthDate, String gender) {
        jdbcTemplate.update("UPDATE user_information SET birth_date = ?, gender = ? WHERE user_id = ?",
                birthDate, gender, bytes(userId));
    }

    private void setInterests(UUID userId, String... categories) {
        jdbcTemplate.update("DELETE FROM user_interests WHERE user_id = ?", bytes(userId));
        for (String c : categories) {
            jdbcTemplate.update("INSERT INTO user_interests (user_id, category) VALUES (?, ?)", bytes(userId), c);
        }
    }

    /** 칸별 증가분 — 움직이지 않은 칸은 뺀다. */
    private static Map<String, Long> diff(List<AdminDtos.Bucket> before, List<AdminDtos.Bucket> after) {
        Map<String, Long> b = before.stream().collect(Collectors.toMap(AdminDtos.Bucket::key, AdminDtos.Bucket::count));
        return after.stream()
                .filter(x -> x.count() != b.getOrDefault(x.key(), 0L))
                .collect(Collectors.toMap(AdminDtos.Bucket::key, x -> x.count() - b.getOrDefault(x.key(), 0L)));
    }

    private static AdminDtos.AgeGender band(MemberProfile p, String band) {
        return p.ageByGender().stream().filter(r -> r.band().equals(band)).findFirst().orElseThrow();
    }

    private static AdminDtos.Consent consent(MemberProfile p, String type) {
        return p.consents().stream().filter(r -> r.type().equals(type)).findFirst().orElseThrow();
    }
}
