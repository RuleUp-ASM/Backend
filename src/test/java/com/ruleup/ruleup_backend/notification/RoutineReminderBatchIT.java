package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.domain.NotificationMute;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.reminder.ReminderSlot;
import com.ruleup.ruleup_backend.notification.reminder.RoutineReminderBatch;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 루틴 리마인더 — 08:00 · 12:00 · 19:00 KST.
 *
 * <p>계약은 셋이다. ① <b>유저당 슬롯당 한 건</b>(스펙의 규모 산정 6만 건이 이 모양을 전제한다)
 * ② <b>음소거한 방은 집계에서 빠지고</b>, 전부 음소거면 리마인더 자체가 없다
 * ③ {@code dedup_key} 에 슬롯이 있어 <b>재실행이 두 줄로 쌓이지 않는다</b>.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class RoutineReminderBatchIT extends ChallengeApiSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RoutineReminderBatch batch;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationMuteRepository muteRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() {
        return mvc;
    }

    @Override
    protected JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    /**
     * 오늘 판정 대상이고 아직 인증하지 않은 방 하나.
     *
     * <p>{@code setup_status} 를 READY 로 올린다. 셋업이 끝나지 않은 멤버는 <b>판정 자체가
     * 스킵</b>되므로 리마인더 대상이 아니고, 그쪽은 셋업 유도 고스트 푸시가 따로 맡는다.
     */
    private UUID joinedRoom(UUID userId) {
        UUID challengeId = insertChallenge(userId, "HEALTH", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, userId, "OWNER");
        jdbcTemplate.update("UPDATE challenge_members SET setup_status = 'READY' "
                + "WHERE challenge_id = ? AND user_id = ?", bytes(challengeId), bytes(userId));
        return challengeId;
    }

    private List<Notification> reminders(UUID userId) {
        return notificationRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(n -> NotificationType.ROUTINE_REMINDER.name().equals(n.getType()))
                .toList();
    }

    private static Instant slotTime(ReminderSlot slot) {
        return LocalDateTime.of(LocalDate.now(KST), java.time.LocalTime.of(slot.hour(), 0))
                .atZone(KST).toInstant();
    }

    @Test
    @DisplayName("오늘 미인증 루틴이 있으면 리마인더가 적재된다 — 딥링크는 방을 가리킨다")
    void remindsPendingRoutine() throws Exception {
        Member me = member(uniq("rm1"));
        UUID challengeId = joinedRoom(me.id());

        batch.send(slotTime(ReminderSlot.MORNING));

        assertThat(reminders(me.id())).singleElement().satisfies(n -> {
            assertThat(n.getDeeplink()).isEqualTo("ruleup://challenges/" + challengeId);
            assertThat(n.getChallengeId()).isEqualTo(challengeId);
            assertThat(n.getDedupKey()).contains(ReminderSlot.MORNING.name());
        });
    }

    @Test
    @DisplayName("같은 슬롯을 다시 돌려도 두 줄로 쌓이지 않는다 — 멀티 태스크 중복 실행을 이 UNIQUE 로 막는다")
    void rerunIsIdempotent() throws Exception {
        Member me = member(uniq("rm2"));
        joinedRoom(me.id());

        batch.send(slotTime(ReminderSlot.MORNING));
        batch.send(slotTime(ReminderSlot.MORNING));

        assertThat(reminders(me.id())).hasSize(1);
    }

    @Test
    @DisplayName("슬롯이 다르면 각각 나간다 — 키에 슬롯이 있어 억제가 필요 없다")
    void differentSlotsAreSeparate() throws Exception {
        Member me = member(uniq("rm3"));
        joinedRoom(me.id());

        batch.send(slotTime(ReminderSlot.MORNING));
        batch.send(slotTime(ReminderSlot.NOON));

        assertThat(reminders(me.id())).hasSize(2);
    }

    @Test
    @DisplayName("여러 방에 미인증 루틴이 있어도 한 건이다 — 유저당 슬롯당 하나")
    void oneReminderPerUserPerSlot() throws Exception {
        Member me = member(uniq("rm4"));
        joinedRoom(me.id());
        joinedRoom(me.id());
        joinedRoom(me.id());

        batch.send(slotTime(ReminderSlot.EVENING));

        assertThat(reminders(me.id())).hasSize(1);
    }

    @Test
    @DisplayName("참여 방을 전부 음소거하면 리마인더 자체가 없다 — 집계에서 빠진다")
    void allMutedMeansNoReminder() throws Exception {
        Member me = member(uniq("rm5"));
        UUID challengeId = joinedRoom(me.id());
        muteRepository.save(NotificationMute.of(me.id(), challengeId, Instant.now()));

        batch.send(slotTime(ReminderSlot.MORNING));

        assertThat(reminders(me.id()))
                .as("발송 단계 음소거와 달리 여기는 적재 자체를 하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("음소거하지 않은 방이 하나라도 남으면 그 방으로 보낸다")
    void partialMuteStillReminds() throws Exception {
        Member me = member(uniq("rm6"));
        UUID muted = joinedRoom(me.id());
        UUID open = joinedRoom(me.id());
        muteRepository.save(NotificationMute.of(me.id(), muted, Instant.now()));

        batch.send(slotTime(ReminderSlot.MORNING));

        assertThat(reminders(me.id())).singleElement()
                .satisfies(n -> assertThat(n.getChallengeId()).isEqualTo(open));
    }

    @Test
    @DisplayName("참여 중인 방이 없으면 대상이 아니다")
    void noRoomsNoReminder() throws Exception {
        Member me = member(uniq("rm7"));

        batch.send(slotTime(ReminderSlot.MORNING));

        assertThat(reminders(me.id())).isEmpty();
    }
}
