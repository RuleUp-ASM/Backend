package com.ruleup.ruleup_backend.watcher;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.notification.NotificationRepository;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.watcher.domain.*;
import com.ruleup.ruleup_backend.watcher.infra.ContactCipher;
import com.ruleup.ruleup_backend.watcher.infra.SmsSender;
import com.ruleup.ruleup_backend.watcher.repository.WatcherInvitationRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherNotificationRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherRepository;
import com.ruleup.ruleup_backend.watcher.service.WatcherNotificationDispatcher;
import com.ruleup.ruleup_backend.watcher.service.WatcherNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 감시자 실패 통지 파이프라인 통합 검증(실 MySQL) — §9/§8.2/§11.4.
 *  - 적재(ACTIVE 감시자만) → 발송 스윕(채널 분기) → SENT.
 *  - 야간 디퍼(22~08시 → 08:00) scheduledAt 계산.
 *  - 멱등(중복 적재 차단), 수신거부 시 발송 중단(SKIPPED).
 */
@Import({TestcontainersConfiguration.class, WatcherNotificationFlowIT.TestConfig.class})
@SpringBootTest
@Transactional
class WatcherNotificationFlowIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String PHONE = "010-1234-5678";

    static class CapturingSmsSender implements SmsSender {
        volatile String lastFailureMessage;
        volatile String lastUnsub;
        public void sendOtp(String phone, String code) { }
        public void sendFailureNotice(String phone, String message, String unsubscribeUrl) {
            this.lastFailureMessage = message; this.lastUnsub = unsubscribeUrl;
        }
    }

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary CapturingSmsSender capturingSmsSender() { return new CapturingSmsSender(); }
    }

    @Autowired WatcherNotificationService notificationService;
    @Autowired WatcherNotificationDispatcher dispatcher;
    @Autowired WatcherRepository watcherRepository;
    @Autowired WatcherInvitationRepository invitationRepository;
    @Autowired WatcherNotificationRepository queueRepository;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired ContactCipher contactCipher;
    @Autowired CapturingSmsSender sms;

    // 한낮(즉시 발송) / 야간(디퍼) 고정 시각.
    private static final Instant DAY = LocalDate.of(2026, 1, 15).atTime(14, 0).atZone(KST).toInstant();
    private static final Instant NIGHT = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(KST).toInstant();
    private static final LocalDate TARGET = LocalDate.of(2026, 1, 15);

    private UUID newUser(String nick) {
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + UUID.randomUUID(), null, nick, null, List.of())).getId();
    }

    private UUID newChallenge(UUID ownerId) {
        Challenge c = Challenge.create(ownerId, "아침 7시 기상", null, null, "EXERCISE",
                ParticipationType.SOLO, null, List.of("MON"), 14, LocalDate.now(),
                null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, false);
        return challengeRepository.saveAndFlush(c).getId();
    }

    private Watcher watcher(UUID owner, UUID challengeId, boolean active, boolean sms, UUID watcherUserId) {
        var inv = invitationRepository.saveAndFlush(WatcherInvitation.create(
                owner, challengeId, "inv_" + UUID.randomUUID(), Instant.now().plus(Duration.ofDays(7))));
        Watcher w = Watcher.invited(inv.getId(), challengeId, owner);
        if (sms) {
            w.consentAsNonUser(contactCipher.encrypt("01012345678"), "010-****-5678",
                    "unsub_" + UUID.randomUUID(), active, Instant.now());
        } else {
            w.consentAsUser(watcherUserId, "감시자", active, Instant.now());
        }
        return watcherRepository.saveAndFlush(w);
    }

    @Test
    @DisplayName("IN_APP ACTIVE 감시자 → 적재 후 스윕이 인앱 통지 발송 + SENT")
    void inApp_immediate_dispatch() {
        UUID owner = newUser("방장");
        UUID watcherUser = newUser("감시자");
        UUID challengeId = newChallenge(owner);
        watcher(owner, challengeId, true, false, watcherUser);

        notificationService.enqueueForFailure(challengeId, owner, TARGET, DAY);
        queueRepository.flush();

        dispatcher.dispatchDue();

        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(watcherUser))
                .anyMatch(n -> n.getType() == NotificationType.WATCHER_ROUTINE_FAILED);
        assertThat(queueRepository.findAll())
                .filteredOn(n -> n.getChallengeId().equals(challengeId))
                .allMatch(n -> n.getStatus() == WatcherNotificationStatus.SENT);
    }

    @Test
    @DisplayName("SMS ACTIVE 감시자 → 스윕이 운영성 SMS(수신거부 링크 포함) 발송")
    void sms_dispatch_includes_unsubscribe() {
        UUID owner = newUser("방장");
        UUID challengeId = newChallenge(owner);
        watcher(owner, challengeId, true, true, null);

        notificationService.enqueueForFailure(challengeId, owner, TARGET, DAY);
        queueRepository.flush();
        dispatcher.dispatchDue();

        assertThat(sms.lastFailureMessage).contains("루틴 약속을 지키지 못했어요");
        assertThat(sms.lastUnsub).contains("/unsubscribe?token=");
    }

    @Test
    @DisplayName("야간(22~08시) 도달분은 08:00로 디퍼 — 스윕이 아직 발송 안 함")
    void night_defer_to_8am() {
        UUID owner = newUser("방장");
        UUID watcherUser = newUser("감시자");
        UUID challengeId = newChallenge(owner);
        watcher(owner, challengeId, true, false, watcherUser);

        notificationService.enqueueForFailure(challengeId, owner, TARGET, NIGHT);
        queueRepository.flush();

        var row = queueRepository.findAll().stream()
                .filter(n -> n.getChallengeId().equals(challengeId)).findFirst().orElseThrow();
        Instant expected = LocalDate.of(2026, 1, 16).atTime(8, 0).atZone(KST).toInstant();
        assertThat(row.getScheduledAt()).isEqualTo(expected);
        assertThat(row.getStatus()).isEqualTo(WatcherNotificationStatus.PENDING);
    }

    @Test
    @DisplayName("같은 감시자×챌린지×날짜는 1건만 적재(멱등)")
    void idempotent_enqueue() {
        UUID owner = newUser("방장");
        UUID watcherUser = newUser("감시자");
        UUID challengeId = newChallenge(owner);
        watcher(owner, challengeId, true, false, watcherUser);

        notificationService.enqueueForFailure(challengeId, owner, TARGET, DAY);
        queueRepository.flush();
        notificationService.enqueueForFailure(challengeId, owner, TARGET, DAY);
        queueRepository.flush();

        assertThat(queueRepository.findAll().stream()
                .filter(n -> n.getChallengeId().equals(challengeId)).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("CONSENTED(미ACTIVE) 감시자에겐 적재하지 않음")
    void only_active_targeted() {
        UUID owner = newUser("방장");
        UUID watcherUser = newUser("감시자");
        UUID challengeId = newChallenge(owner);
        watcher(owner, challengeId, false, false, watcherUser);  // CONSENTED

        notificationService.enqueueForFailure(challengeId, owner, TARGET, DAY);
        queueRepository.flush();

        assertThat(queueRepository.findAll())
                .filteredOn(n -> n.getChallengeId().equals(challengeId)).isEmpty();
    }

    @Test
    @DisplayName("적재 후 수신거부 → 스윕은 발송하지 않고 SKIPPED")
    void revoked_before_dispatch_is_skipped() {
        UUID owner = newUser("방장");
        UUID watcherUser = newUser("감시자");
        UUID challengeId = newChallenge(owner);
        Watcher w = watcher(owner, challengeId, true, false, watcherUser);

        notificationService.enqueueForFailure(challengeId, owner, TARGET, DAY);
        queueRepository.flush();

        w.revoke(Instant.now());                 // 발송 전에 수신거부/해제
        watcherRepository.saveAndFlush(w);

        dispatcher.dispatchDue();

        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(watcherUser)).isEmpty();
        assertThat(queueRepository.findAll())
                .filteredOn(n -> n.getChallengeId().equals(challengeId))
                .allMatch(n -> n.getStatus() == WatcherNotificationStatus.SKIPPED);
    }
}
