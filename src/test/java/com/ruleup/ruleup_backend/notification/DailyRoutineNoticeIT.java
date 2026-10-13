package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.repository.NotificationRepository;
import com.ruleup.ruleup_backend.verification.VerificationApiSupport;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationRequest;
import com.ruleup.ruleup_backend.verification.service.FailExpectedNoticeJob;
import com.ruleup.ruleup_backend.verification.service.VerificationManualService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 하루 단위 루틴 알림 두 가지(2026-10-13).
 *
 * <ul>
 *   <li>「오늘 루틴을 모두 완료했어요」 — 오늘 해야 할 루틴이 전부 성공한 순간 한 번. 대상이 하나뿐이면 개별 성공
 *       알림을 대신한다.
 *   <li>「[루틴] 인증을 놓쳤어요」(수동 방) — 체크하지 않고 하루가 지나면 다음 날 자정 직후. 수동 방은 미체크 날에
 *       판정 행이 없어 그동안 아무 알림도 없었다.
 * </ul>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class DailyRoutineNoticeIT extends VerificationApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired NotificationRepository notificationRepository;
    @Autowired VerificationManualService manualService;
    @Autowired FailExpectedNoticeJob failExpectedJob;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private List<Notification> ofType(UUID userId, String type) {
        return notificationRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(n -> type.equals(n.getType())).toList();
    }

    /** 오늘 판정 대상인 수동 체크 방. */
    private UUID manualRoom(Member me, String title) {
        UUID id = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
        jdbc().update("UPDATE challenges SET title = ?, ai_title = ? WHERE id = ?", title, title, bytes(id));
        insertActiveMembership(id, me.id(), "OWNER");
        return id;
    }

    private void check(Member me, UUID challengeId) {
        manualService.submit(me.id(), challengeId, new ManualVerificationRequest(null, null));
    }

    @Test
    @DisplayName("오늘 해야 할 루틴이 하나뿐이면 체크한 순간 「모두 완료」만 온다 — 개별 성공 알림과 겹치지 않는다")
    void singleRoutineCompletionReplacesIndividual() throws Exception {
        Member me = member(uniq("drc1"));
        UUID room = manualRoom(me, "물 마시기");

        check(me, room);

        assertThat(ofType(me.id(), "DAILY_ROUTINES_COMPLETED")).singleElement().satisfies(n -> {
            assertThat(n.getTitle()).isEqualTo("오늘 루틴을 모두 완료했어요");
            assertThat(n.getBody()).isEqualTo("오늘 해야 할 루틴 1개를 전부 해냈어요. 수고하셨어요!");
            assertThat(n.getDeeplink()).isEqualTo("ruleup://home");
        });
        assertThat(ofType(me.id(), "VERIFICATION_RESULT")).isEmpty();
    }

    @Test
    @DisplayName("루틴이 둘이면 첫 체크엔 개별 성공만, 마지막 체크에 개별 성공과 「모두 완료」가 함께 온다")
    void completionAfterLastOfMany() throws Exception {
        Member me = member(uniq("drc2"));
        UUID water = manualRoom(me, "물 마시기");
        UUID run = manualRoom(me, "러닝");

        check(me, water);
        assertThat(ofType(me.id(), "DAILY_ROUTINES_COMPLETED")).isEmpty();
        assertThat(ofType(me.id(), "VERIFICATION_RESULT")).singleElement()
                .satisfies(n -> assertThat(n.getTitle()).isEqualTo("[물 마시기] 인증이 완료됐어요"));

        check(me, run);
        assertThat(ofType(me.id(), "VERIFICATION_RESULT")).hasSize(2);
        assertThat(ofType(me.id(), "DAILY_ROUTINES_COMPLETED")).singleElement()
                .satisfies(n -> assertThat(n.getBody()).contains("2개"));
    }

    @Test
    @DisplayName("수동 방에서 어제 체크하지 않았으면 자정 배치가 「[루틴] 인증을 놓쳤어요」를 한 번 보낸다 — 체크한 방은 빠진다")
    void manualMissedYesterday() throws Exception {
        Member me = member(uniq("drm1"));
        LocalDate yesterday = LocalDate.now(KST).minusDays(1);
        UUID missed = manualRoom(me, "독서");
        UUID checked = manualRoom(me, "명상");
        for (UUID id : List.of(missed, checked)) {
            jdbc().update("UPDATE challenges SET start_date = ? WHERE id = ?", yesterday.minusDays(2), bytes(id));
            jdbc().update("UPDATE challenge_members SET joined_at = ? WHERE challenge_id = ?",
                    java.sql.Timestamp.valueOf(yesterday.minusDays(3).atStartOfDay()), bytes(id));
        }
        UUID memberId = jdbc().queryForObject("SELECT id FROM challenge_members WHERE challenge_id = ?",
                (rs, i) -> { java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(rs.getBytes(1)); return new UUID(b.getLong(), b.getLong()); }, bytes(checked));
        jdbc().update("INSERT INTO VerificationDaily (id, challengeMemberId, challengeId, userId, targetDate, status, method) "
                        + "VALUES (?, ?, ?, ?, ?, 'SUCCESS', 'SELF_CHECK')",
                bytes(UUID.randomUUID()), bytes(memberId), bytes(checked), bytes(me.id()), yesterday);

        failExpectedJob.notifyManualMissed(yesterday);
        failExpectedJob.notifyManualMissed(yesterday);

        assertThat(ofType(me.id(), "ROUTINE_MISSED")).singleElement().satisfies(n -> {
            assertThat(n.getTitle()).isEqualTo("[독서] 인증을 놓쳤어요");
            assertThat(n.getChallengeId()).isEqualTo(missed);
            assertThat(n.getDeeplink()).isEqualTo("ruleup://challenge/" + missed);
        });
    }
}
