package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.agreement.UserAgreementStateRepository;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreementState;
import com.ruleup.ruleup_backend.notification.consumer.NotificationDispatcher;
import com.ruleup.ruleup_backend.notification.consumer.SuppressedReason;
import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationTab;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.domain.UserNotificationSetting;
import com.ruleup.ruleup_backend.notification.queue.NotificationMessage;
import com.ruleup.ruleup_backend.push.domain.DevicePlatform;
import com.ruleup.ruleup_backend.push.domain.DeviceToken;
import com.ruleup.ruleup_backend.push.repository.DeviceTokenRepository;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨슈머 — <b>묶음 조회와 pushed_at 갱신</b>이 계약이다.
 *
 * <p>판정 규칙 자체는 {@link NotificationDispatchDecisionTest} 가 순수 함수로 지킨다. 여기서
 * 확인하는 것은 DB 를 끼고서야 드러나는 것들이다 — 억제 기준이 {@code created_at} 이 아니라
 * <b>{@code pushed_at}</b> 인지, 억제 대상 타입만 갱신하는지, 야간에 걸린 건이 <b>버려지지 않는지</b>.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class NotificationConsumerIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired NotificationPublisher publisher;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationSettingRepository settingRepository;
    @Autowired NotificationMuteRepository muteRepository;
    @Autowired DeviceTokenRepository deviceTokenRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired NotificationTestQueue.RecordingPushSender pushSender;
    @Autowired UserAgreementStateRepository agreementStateRepository;

    /**
     * 음소거 행은 챌린지에 FK 가 걸려 있어 실재하는 방이 필요하다 — 그래야 「탈퇴한 방의
     * 음소거가 남는다」 같은 상태가 애초에 생기지 않는다.
     */
    private UUID insertChallenge(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO challenges " +
                        "(id, owner_id, title, ai_title, description, category, mode, capacity, " +
                        " repeat_days, duration_days, start_date, end_date, verification_config, " +
                        " params, penalty_config, reward_config, anonymity, status, " +
                        " moderation_status, ai_assisted, participant_count) " +
                        "VALUES (?, ?, '음소거방', '음소거방', '설명', 'HEALTH', 'GROUP', 50, " +
                        " '[\"MON\"]', 14, DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), " +
                        " DATE_ADD(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 14 DAY), " +
                        " '{\"selectedMethod\":\"MANUAL\",\"verificationType\":\"MANUAL\"," +
                        "\"signalSource\":\"SELF_CHECK\",\"wearableReq\":\"NONE\"," +
                        "\"requiredPermissions\":[]}', '{}', '{\"mannerDeduction\":1.0}', " +
                        " '{\"mannerGain\":1.0}', 'REAL', 'ACTIVE', 'NONE', 1, 1)",
                bytes(id), bytes(ownerId));
        return id;
    }

    private static byte[] bytes(UUID u) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    private static Instant kstAt(int hour) {
        return LocalDateTime.of(2026, 9, 8, hour, 0).atZone(KST).toInstant();
    }

    /** {@code nickname} 은 VARCHAR(12) 이고 스위트가 DB 를 공유한다 — 짧고 겹치지 않아야 한다. */
    private static String nickname() {
        return "%s%s".formatted("c", Long.toString(System.nanoTime(), 36));
    }

    private UUID newUser() {
        String tag = "cs" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com", nickname(), null, List.of())).getId();
    }

    /** 활성 기기가 있어야 NO_DEVICE 로 걸리지 않는다. */
    private UUID userWithDevice() {
        UUID userId = newUser();
        deviceTokenRepository.save(DeviceToken.create(userId,
                "tok-" + SEQ.incrementAndGet() + "-" + System.nanoTime(),
                DevicePlatform.ANDROID, Instant.now()));
        return userId;
    }

    private Notification store(UUID userId, NotificationType type, Map<String, String> params) {
        return txTemplate.execute(t -> publisher.publish(
                NotificationEvent.of(userId, type, "제목", "본문", params)).orElseThrow());
    }

    private static NotificationMessage messageOf(Notification n) {
        return NotificationMessage.from(n);
    }

    // =====================================================================
    @Nested
    @DisplayName("pushed_at — 억제 판정의 기준")
    class PushedAt {

        @Test
        @DisplayName("억제 대상 타입만 갱신한다 — 08:00 피크의 쓰기를 줄인다")
        void onlySuppressibleTypesAreStamped() {
            UUID userId = userWithDevice();
            Notification suppressible = store(userId, NotificationType.WATCHER_REACTION,
                    Map.of(NotificationParams.EVENT_KEY, "r" + SEQ.incrementAndGet(),
                            NotificationParams.CHALLENGE_ID, "c1",
                            NotificationParams.SENDER_ID, "s1"));
            Notification plain = store(userId, NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.EVENT_KEY, "s" + SEQ.incrementAndGet()));

            dispatcher.dispatch(List.of(messageOf(suppressible), messageOf(plain)), kstAt(12));

            assertThat(reload(suppressible).getPushedAt()).isNotNull();
            assertThat(reload(plain).getPushedAt())
                    .as("억제를 안 쓰는 타입은 갱신하지 않는다").isNull();
        }

        @Test
        @DisplayName("억제된 행에는 pushed_at 을 남기지 않는다 — 남기면 억제가 영원히 풀리지 않는다")
        void suppressedRowsAreNotStamped() {
            UUID userId = userWithDevice();
            Map<String, String> params = Map.of(
                    NotificationParams.CHALLENGE_ID, "c" + SEQ.incrementAndGet(),
                    NotificationParams.SENDER_ID, "s1");

            Notification first = store(userId, NotificationType.WATCHER_REACTION,
                    withKey(params, "r1-" + SEQ.get()));
            dispatcher.dispatch(List.of(messageOf(first)), kstAt(12));

            Notification second = store(userId, NotificationType.WATCHER_REACTION,
                    withKey(params, "r2-" + SEQ.get()));
            var outcomes = dispatcher.dispatch(List.of(messageOf(second)), kstAt(13));

            assertThat(outcomes.getFirst().suppressedReason())
                    .isEqualTo(SuppressedReason.INTERVAL);
            assertThat(reload(second).getPushedAt())
                    .as("억제된 행이 시각을 남기면 직전 행이 항상 윈도우 안에 있게 된다").isNull();
        }

        @Test
        @DisplayName("인터벌을 넘기면 다시 나간다 — 기준은 발송 성공 시각이다")
        void intervalReleasesAfterTheWindow() {
            UUID userId = userWithDevice();
            Map<String, String> params = Map.of(
                    NotificationParams.CHALLENGE_ID, "c" + SEQ.incrementAndGet(),
                    NotificationParams.SENDER_ID, "s1");

            Notification first = store(userId, NotificationType.WATCHER_REACTION,
                    withKey(params, "w1-" + SEQ.get()));
            dispatcher.dispatch(List.of(messageOf(first)), kstAt(12));

            Notification later = store(userId, NotificationType.WATCHER_REACTION,
                    withKey(params, "w2-" + SEQ.get()));
            var outcomes = dispatcher.dispatch(List.of(messageOf(later)),
                    kstAt(12).plus(Duration.ofHours(25)));

            assertThat(outcomes.getFirst().suppressedReason()).isNull();
        }

        private Map<String, String> withKey(Map<String, String> base, String eventKey) {
            return Map.of(NotificationParams.EVENT_KEY, eventKey,
                    NotificationParams.CHALLENGE_ID, base.get(NotificationParams.CHALLENGE_ID),
                    NotificationParams.SENDER_ID, base.get(NotificationParams.SENDER_ID));
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("발송 판정 — DB 를 끼고")
    class Gates {

        @Test
        @DisplayName("야간이면 미룬다 — 메시지를 삭제하지 않는다")
        void nightDefers() {
            UUID userId = userWithDevice();
            Notification n = store(userId, NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.EVENT_KEY, "n" + SEQ.incrementAndGet()));

            var outcome = dispatcher.dispatch(List.of(messageOf(n)), kstAt(23)).getFirst();

            assertThat(outcome.deferred()).isTrue();
            assertThat(outcome.deletable()).as("큐에 남아 08:00 에 다시 집힌다").isFalse();
            assertThat(reload(n).getPushedAt()).isNull();
        }

        @Test
        @DisplayName("읽음 지점 아래면 보내지 않고 메시지는 삭제한다")
        void alreadyReadIsDeleted() {
            UUID userId = userWithDevice();
            Notification n = store(userId, NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.EVENT_KEY, "rd" + SEQ.incrementAndGet()));

            txTemplate.executeWithoutResult(t -> {
                UserNotificationSetting s = UserNotificationSetting.defaults(userId, Instant.now());
                s.advanceReadCursor(NotificationTab.NOTIFICATION, n.getId(), Instant.now());
                settingRepository.save(s);
            });

            var outcome = dispatcher.dispatch(List.of(messageOf(n)), kstAt(12)).getFirst();

            assertThat(outcome.suppressedReason()).isEqualTo(SuppressedReason.ALREADY_READ);
            assertThat(outcome.deletable()).isTrue();
        }

        @Test
        @DisplayName("활성 기기가 없으면 NO_DEVICE — 알림함으로만 도달한다")
        void noDevice() {
            UUID userId = newUser();   // 기기를 등록하지 않는다
            Notification n = store(userId, NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.EVENT_KEY, "nd" + SEQ.incrementAndGet()));

            assertThat(dispatcher.dispatch(List.of(messageOf(n)), kstAt(12)).getFirst()
                    .suppressedReason()).isEqualTo(SuppressedReason.NO_DEVICE);
        }

        @Test
        @DisplayName("음소거한 방의 알림은 막힌다 — 설정을 DB 에서 묶음 조회한다")
        void mutedChallenge() {
            UUID userId = userWithDevice();
            UUID challengeId = insertChallenge(userId);
            Notification n = store(userId, NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.EVENT_KEY, "mu" + SEQ.incrementAndGet()));

            NotificationMessage muted = new NotificationMessage(n.getId(), userId,
                    NotificationType.VERIFICATION_RESULT.name(),
                    NotificationType.VERIFICATION_RESULT.toggleGroup(), challengeId,
                    NotificationTab.NOTIFICATION, "제목", "본문", null, null);
            txTemplate.executeWithoutResult(t -> muteRepository.save(
                    com.ruleup.ruleup_backend.notification.domain.NotificationMute.of(
                            userId, challengeId, Instant.now())));

            assertThat(dispatcher.dispatch(List.of(muted), kstAt(12)).getFirst().suppressedReason())
                    .isEqualTo(SuppressedReason.MUTED);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("묶음 처리")
    class Batching {

        @Test
        @DisplayName("여러 유저가 섞여 있어도 각자의 설정으로 판정한다")
        void perUserSettingsInOneBatch() {
            UUID sending = userWithDevice();
            UUID master0ff = userWithDevice();
            txTemplate.executeWithoutResult(t -> {
                UserNotificationSetting s = UserNotificationSetting.defaults(master0ff, Instant.now());
                s.applyMaster(false, Instant.now());
                settingRepository.save(s);
            });

            Notification a = store(sending, NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.EVENT_KEY, "b1" + SEQ.incrementAndGet()));
            Notification b = store(master0ff, NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.EVENT_KEY, "b2" + SEQ.incrementAndGet()));

            var outcomes = dispatcher.dispatch(List.of(messageOf(a), messageOf(b)), kstAt(12));

            assertThat(outcomes.get(0).suppressedReason()).isNull();
            assertThat(outcomes.get(1).suppressedReason()).isEqualTo(SuppressedReason.MASTER_OFF);
        }

        @Test
        @DisplayName("여러 유저의 토큰을 묶음으로 해결한다 — 알림마다 다시 물으면 08:00 묶음이 밀린다")
        void resolvesTokensForWholeBatch() {
            pushSender.reset();
            UUID first = userWithDevice();
            UUID second = userWithDevice();
            String firstToken = deviceTokenRepository.findByUserId(first).getFirst().getToken();
            String secondToken = deviceTokenRepository.findByUserId(second).getFirst().getToken();

            Notification a = store(first, NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.EVENT_KEY, "tk1" + SEQ.incrementAndGet()));
            Notification b = store(second, NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.EVENT_KEY, "tk2" + SEQ.incrementAndGet()));

            dispatcher.dispatch(List.of(messageOf(a), messageOf(b)), kstAt(12));

            assertThat(pushSender.sent).hasSize(2);
            assertThat(pushSender.sent.get(0).tokens())
                    .as("각 알림이 자기 유저의 토큰을 들고 간다").containsExactly(firstToken);
            assertThat(pushSender.sent.get(1).tokens()).containsExactly(secondToken);
        }

        @Test
        @DisplayName("활성 기기가 없으면 전송기까지 가지 않는다 — 판정에서 걸린다")
        void noDeviceNeverReachesSender() {
            pushSender.reset();
            UUID userId = newUser();   // 기기 없음
            Notification n = store(userId, NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.EVENT_KEY, "nd" + SEQ.incrementAndGet()));

            var outcomes = dispatcher.dispatch(List.of(messageOf(n)), kstAt(12));

            assertThat(outcomes.getFirst().suppressedReason()).isEqualTo(SuppressedReason.NO_DEVICE);
            assertThat(pushSender.sent).isEmpty();
        }

        @Test
        @DisplayName("빈 묶음은 아무 조회도 하지 않는다")
        void emptyBatch() {
            assertThat(dispatcher.dispatch(List.of(), kstAt(12))).isEmpty();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("마케팅 수신 동의 — 약관 상태가 원본이다")
    class MarketingConsent {

        @Test
        @DisplayName("동의 행이 없으면 막힌다 — 가입 때 거부하면 설정 행도 없어 토글은 ON 으로 읽힌다")
        void noAgreementRowBlocks() {
            // 약관 행을 만들지 않는다. 설정 행도 없으므로 그룹 토글은 ON 으로 해석된다 —
            // 토글만 보던 구 판정이 바로 이 사람에게 광고를 보냈다.
            UUID userId = userWithDevice();
            Notification n = store(userId, NotificationType.MARKETING, marketingParams());

            var outcomes = dispatcher.dispatch(List.of(messageOf(n)), kstAt(12));

            assertThat(outcomes.getFirst().suppressedReason())
                    .isEqualTo(SuppressedReason.MARKETING_CONSENT_OFF);
        }

        @Test
        @DisplayName("동의했으면 나간다")
        void consentedSends() {
            UUID userId = userWithDevice();
            consent(userId, true);
            Notification n = store(userId, NotificationType.MARKETING, marketingParams());

            var outcomes = dispatcher.dispatch(List.of(messageOf(n)), kstAt(12));

            assertThat(outcomes.getFirst().sent()).isTrue();
        }

        @Test
        @DisplayName("동의 후 철회했으면 막힌다 — agreed=false 는 미동의와 같다")
        void revokedBlocks() {
            UUID userId = userWithDevice();
            consent(userId, false);
            Notification n = store(userId, NotificationType.MARKETING, marketingParams());

            var outcomes = dispatcher.dispatch(List.of(messageOf(n)), kstAt(12));

            assertThat(outcomes.getFirst().suppressedReason())
                    .isEqualTo(SuppressedReason.MARKETING_CONSENT_OFF);
        }

        @Test
        @DisplayName("미동의자의 제재 고지는 그대로 나간다 — 동의 게이트는 마케팅에만 적용된다")
        void otherGroupsUnaffected() {
            UUID userId = userWithDevice();   // 마케팅 미동의
            Notification n = store(userId, NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.EVENT_KEY, "mc" + SEQ.incrementAndGet()));

            var outcomes = dispatcher.dispatch(List.of(messageOf(n)), kstAt(12));

            assertThat(outcomes.getFirst().sent()).isTrue();
        }
    }

    private void consent(UUID userId, boolean agreed) {
        txTemplate.executeWithoutResult(t -> agreementStateRepository.save(
                UserAgreementState.of(userId, AgreementType.MARKETING, agreed, "1.0",
                        Instant.now())));
    }

    /** 마케팅은 멱등키가 {@code event_key}, 억제키가 {@code campaign_id} 다. */
    private static Map<String, String> marketingParams() {
        String key = "mk" + SEQ.incrementAndGet() + System.nanoTime();
        return Map.of(NotificationParams.EVENT_KEY, key, NotificationParams.CAMPAIGN_ID, key);
    }

    private Notification reload(Notification n) {
        return notificationRepository.findById(n.getId()).orElseThrow();
    }
}
