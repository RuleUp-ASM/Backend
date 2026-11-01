package com.ruleup.ruleup_backend.observability.usage;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.RoomCell;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.RoomComposition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 서비스 이용 집계 — 방 구성·자동 인증·관심사·티어·탈퇴 단계·화면 조회.
 *
 * <p>테스트 DB 는 스위트 전체가 공유해 다른 시험이 만든 회원·방이 섞여 있다. 그래서 절댓값이 아니라 <b>심기 전·후의 차이</b>로
 * 단언한다({@link com.ruleup.ruleup_backend.admin.MemberProfileIT} 와 같다).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UsageStatsIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UsageStatsService stats;
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
    @DisplayName("방 구성 — 모드·인증 방식·상태별 방 수와 인원, 끝난 방·나간 멤버·운영자는 빼고, 사람 수는 모드마다 중복 없이")
    void roomComposition() throws Exception {
        RoomComposition before = stats.rooms();

        Member a = member(uniq("rca"));   // 그룹 자동 방장 + 솔로 수동
        Member b = member(uniq("rcb"));   // 그룹 자동 멤버
        Member c = member(uniq("rcc"));   // 그룹 자동에서 나감 — 안 센다
        Member op = member(uniq("rco"));  // 운영자 — 회원이 아니다
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(op.id()));

        UUID groupAuto = room(a.id(), "ACTIVE", "GROUP", "AUTO");
        insertActiveMembership(groupAuto, a.id(), "OWNER");
        insertActiveMembership(groupAuto, b.id(), "MEMBER");
        insertActiveMembership(groupAuto, c.id(), "MEMBER");
        jdbcTemplate.update("UPDATE challenge_members SET status = 'LEFT' WHERE challenge_id = ? AND user_id = ?",
                bytes(groupAuto), bytes(c.id()));
        UUID soloManual = room(a.id(), "UPCOMING", "SOLO", null);     // verification_type 이 빈 옛 행 → 설정의 MANUAL
        insertActiveMembership(soloManual, a.id(), "OWNER");
        insertActiveMembership(room(a.id(), "COMPLETED", "GROUP", "AUTO"), b.id(), "MEMBER");  // 끝난 방
        insertActiveMembership(room(op.id(), "ACTIVE", "GROUP", "MANUAL"), op.id(), "OWNER");  // 운영자만 있는 방

        RoomComposition after = stats.rooms();

        assertThat(cell(after, "GROUP", "AUTO", "ACTIVE").rooms() - cell(before, "GROUP", "AUTO", "ACTIVE").rooms()).isEqualTo(1);
        assertThat(cell(after, "GROUP", "AUTO", "ACTIVE").memberships() - cell(before, "GROUP", "AUTO", "ACTIVE").memberships())
                .as("나간 멤버는 빼고 2명").isEqualTo(2);
        assertThat(cell(after, "SOLO", "MANUAL", "UPCOMING").rooms() - cell(before, "SOLO", "MANUAL", "UPCOMING").rooms()).isEqualTo(1);
        assertThat(cell(after, "GROUP", "MANUAL", "ACTIVE").rooms() - cell(before, "GROUP", "MANUAL", "ACTIVE").rooms())
                .as("운영자만 있는 방은 안 센다").isZero();
        assertThat(after.people().group() - before.people().group()).isEqualTo(2);
        assertThat(after.people().solo() - before.people().solo()).isEqualTo(1);
        assertThat(after.people().auto() - before.people().auto()).isEqualTo(2);
        assertThat(after.people().manual() - before.people().manual()).isEqualTo(1);
        assertThat(after.people().total() - before.people().total()).as("a 는 두 칸에 다 있어도 한 명").isEqualTo(2);
    }

    // ===== 내부 =====

    private UUID room(UUID ownerId, String status, String mode, String verificationType) {
        UUID id = insertChallenge(ownerId, "EXERCISE", status, mode);
        jdbcTemplate.update("UPDATE challenges SET verification_type = ? WHERE id = ?", verificationType, bytes(id));
        return id;
    }

    private static RoomCell cell(RoomComposition r, String mode, String verification, String status) {
        return r.cells().stream()
                .filter(c -> c.mode().equals(mode) && verification.equals(c.verification()) && c.status().equals(status))
                .findFirst().orElse(new RoomCell(mode, verification, status, 0, 0));
    }
}
