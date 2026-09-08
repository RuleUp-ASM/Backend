package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.consumer.DispatchDecision;
import com.ruleup.ruleup_backend.notification.consumer.DispatchInputs;
import com.ruleup.ruleup_backend.notification.consumer.SuppressedReason;
import com.ruleup.ruleup_backend.notification.domain.NotificationTab;
import com.ruleup.ruleup_backend.notification.domain.NotificationToggleGroup;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.domain.UserNotificationSetting;
import com.ruleup.ruleup_backend.notification.queue.NotificationMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발송 판정 — 공통 10절의 순서가 그대로 계약이다.
 *
 * <p>읽음 → 야간 → 마스터 → 그룹 → 음소거 → 마케팅 창 → 인터벌 억제 → 활성 기기.
 * <b>순서를 지키는 것이 값보다 중요하다</b> — 야간이 마스터보다 뒤로 가면 토글이 꺼진 알림이
 * 야간에 버려지고, 08:00 에 토글을 다시 켜도 되살아나지 않는다.
 *
 * <p>어느 경로로 걸러지든 <b>알림 센터에는 이미 적재돼 있다</b>. 여기 판정은 전부 푸시에만 적용된다.
 */
class NotificationDispatchDecisionTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** UUIDv7 은 상위 48비트가 밀리초라 값이 클수록 최신이다. */
    private static UUID idAt(long millis) {
        return new UUID((millis << 16) | 0x7000L, 0x8000_0000_0000_0001L);
    }

    private static final UUID OLDER = idAt(1_700_000_000_000L);
    private static final UUID NEWER = idAt(1_700_000_100_000L);

    private static Instant kst(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 8, hour, minute).atZone(KST).toInstant();
    }

    private static NotificationMessage message(NotificationType type, UUID id, UUID challengeId) {
        return new NotificationMessage(id, USER, type.name(), type.toggleGroup(), challengeId,
                type.tab(), "제목", "본문", "ruleup://home",
                type.suppressKey(Map.of("challenge_id", "c1", "routine_id", "r1",
                        "target_user_id", "u1", "sender_id", "s1", "direction", "UP",
                        "permission", "CAMERA", "campaign_id", "cp1")));
    }

    /** 아무것도 막지 않는 상태 — 설정 기본값, 음소거 없음, 억제 이력 없음, 기기 있음. */
    private static DispatchInputs allow() {
        return new DispatchInputs(UserNotificationSetting.defaults(USER, kst(12, 0)),
                Set.of(), Map.of(), true);
    }

    /** 두 탭의 읽음 지점을 같은 값으로 올린 상태. */
    private static DispatchInputs readUpTo(UUID id) {
        UserNotificationSetting s = UserNotificationSetting.defaults(USER, kst(12, 0));
        s.advanceReadCursor(NotificationTab.NOTIFICATION, id, kst(12, 0));
        s.advanceReadCursor(NotificationTab.ANNOUNCEMENT, id, kst(12, 0));
        return allow().withSettings(s);
    }

    @Nested
    @DisplayName("순서")
    class Order {

        @Test
        @DisplayName("아무것도 막지 않으면 보낸다")
        void sends() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, NEWER, null), allow(), kst(12, 0)))
                    .isEqualTo(DispatchDecision.send());
        }

        @Test
        @DisplayName("읽음이 야간보다 먼저다 — 야간 보류 중 알림함에 들어온 건은 08:00 에 울릴 이유가 없다")
        void readBeatsNight() {
            DispatchInputs read = readUpTo(NEWER);

            DispatchDecision d = DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, OLDER, null), read, kst(23, 0));

            assertThat(d.suppressedReason()).isEqualTo(SuppressedReason.ALREADY_READ);
        }

        @Test
        @DisplayName("야간이 마스터보다 먼저다 — 야간에 버리면 08:00 에 토글을 켜도 되살아나지 않는다")
        void nightBeatsMaster() {
            UserNotificationSetting off = UserNotificationSetting.defaults(USER, kst(23, 0));
            off.applyMaster(false, kst(23, 0));

            DispatchDecision d = DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, NEWER, null),
                    allow().withSettings(off), kst(23, 0));

            assertThat(d.deferred()).as("버리지 않고 미룬다").isTrue();
            assertThat(d.suppressedReason()).isNull();
        }
    }

    @Nested
    @DisplayName("읽음 스킵")
    class AlreadyRead {

        @Test
        @DisplayName("읽음 지점보다 오래된 알림은 보내지 않는다")
        void olderThanCursor() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, OLDER, null),
                    readUpTo(NEWER), kst(12, 0)).suppressedReason())
                    .isEqualTo(SuppressedReason.ALREADY_READ);
        }

        @Test
        @DisplayName("읽음 지점 자신도 읽은 것이다")
        void equalToCursor() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, NEWER, null),
                    readUpTo(NEWER), kst(12, 0)).suppressedReason())
                    .isEqualTo(SuppressedReason.ALREADY_READ);
        }

        @Test
        @DisplayName("읽음 지점보다 최신이면 보낸다")
        void newerThanCursor() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, NEWER, null),
                    readUpTo(OLDER), kst(12, 0)).shouldSend()).isTrue();
        }
    }

    @Nested
    @DisplayName("야간 — 21:00~08:00 KST")
    class Night {

        @Test
        @DisplayName("21:00 정각부터 미룬다")
        void nightStartsAtNine() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, NEWER, null), allow(), kst(21, 0))
                    .deferred()).isTrue();
        }

        @Test
        @DisplayName("08:00 정각부터 보낸다")
        void morningStartsAtEight() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, NEWER, null), allow(), kst(8, 0))
                    .shouldSend()).isTrue();
        }

        @Test
        @DisplayName("강퇴·잠금 고지도 예외가 아니다 — 절대 규칙 2")
        void noExceptionForSanctions() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.CHALLENGE_KICKED, NEWER, null), allow(), kst(22, 30))
                    .deferred()).isTrue();
        }
    }

    @Nested
    @DisplayName("설정 3계층 — 가장 제한적인 것이 이긴다")
    class Settings {

        @Test
        @DisplayName("마스터가 꺼져 있으면 그룹이 켜져 있어도 막힌다")
        void masterOff() {
            UserNotificationSetting s = UserNotificationSetting.defaults(USER, kst(12, 0));
            s.applyMaster(false, kst(12, 0));

            assertThat(DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, NEWER, null),
                    allow().withSettings(s), kst(12, 0)).suppressedReason())
                    .isEqualTo(SuppressedReason.MASTER_OFF);
        }

        @Test
        @DisplayName("마스터가 꺼지면 리마인더도 막힌다 — 상시라도 마스터 아래다")
        void masterOffStopsReminder() {
            UserNotificationSetting s = UserNotificationSetting.defaults(USER, kst(12, 0));
            s.applyMaster(false, kst(12, 0));

            assertThat(DispatchDecision.decide(
                    message(NotificationType.ROUTINE_REMINDER, NEWER, null),
                    allow().withSettings(s), kst(12, 0)).suppressedReason())
                    .isEqualTo(SuppressedReason.MASTER_OFF);
        }

        @Test
        @DisplayName("그룹 토글이 꺼져 있으면 그 그룹만 막힌다")
        void groupOff() {
            UserNotificationSetting s = UserNotificationSetting.defaults(USER, kst(12, 0));
            s.applyGroup(NotificationToggleGroup.CHALLENGE, false, kst(12, 0));
            DispatchInputs in = allow().withSettings(s);

            assertThat(DispatchDecision.decide(
                    message(NotificationType.VERIFICATION_RESULT, NEWER, null), in, kst(12, 0))
                    .suppressedReason()).isEqualTo(SuppressedReason.GROUP_OFF);
            assertThat(DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, NEWER, null), in, kst(12, 0))
                    .shouldSend()).isTrue();
        }

        @Test
        @DisplayName("리마인더는 그룹 판정을 통과한다 — 그룹 토글이 없다")
        void reminderPassesGroupGate() {
            UserNotificationSetting s = UserNotificationSetting.defaults(USER, kst(12, 0));
            s.applyGroup(NotificationToggleGroup.CHALLENGE, false, kst(12, 0));

            assertThat(DispatchDecision.decide(
                    message(NotificationType.ROUTINE_REMINDER, NEWER, null),
                    allow().withSettings(s), kst(12, 0)).shouldSend()).isTrue();
        }
    }

    @Nested
    @DisplayName("챌린지 음소거")
    class Muted {

        private static final UUID ROOM = UUID.randomUUID();

        @Test
        @DisplayName("음소거한 방의 알림은 막힌다")
        void mutedRoom() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.VERIFICATION_RESULT, NEWER, ROOM),
                    allow().withMuted(Set.of(ROOM)), kst(12, 0)).suppressedReason())
                    .isEqualTo(SuppressedReason.MUTED);
        }

        @Test
        @DisplayName("다른 방은 영향받지 않는다")
        void otherRoom() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.VERIFICATION_RESULT, NEWER, UUID.randomUUID()),
                    allow().withMuted(Set.of(ROOM)), kst(12, 0)).shouldSend()).isTrue();
        }

        @Test
        @DisplayName("방이 없는 알림은 음소거 대상이 아니다 — 티어는 유저 단위 점수다")
        void noChallengeContext() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.TIER_CHANGED, NEWER, null),
                    allow().withMuted(Set.of(ROOM)), kst(12, 0)).shouldSend()).isTrue();
        }
    }

    @Nested
    @DisplayName("마케팅 발송 창 — 08~21시(정보통신망법)")
    class MarketingWindow {

        @Test
        @DisplayName("창 안이면 보낸다")
        void insideWindow() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.MARKETING, NEWER, null), allow(), kst(20, 59))
                    .shouldSend()).isTrue();
        }

        @Test
        @DisplayName("창 밖이면 미루지 않고 버린다 — 광고를 미뤄 보낼 이유가 없다")
        void outsideWindowIsDroppedNotDeferred() {
            // 야간 게이트가 먼저 잡아 미루는 것이 정상 경로다. 이 게이트는 시계 오차·경계 계산이
            // 틀렸을 때를 막는 최후 방어라, 야간 판정을 통과한 상태를 직접 만들어 확인한다.
            assertThat(DispatchDecision.decideMarketingWindow(kst(21, 30))).isFalse();
            assertThat(DispatchDecision.decideMarketingWindow(kst(7, 59))).isFalse();
            assertThat(DispatchDecision.decideMarketingWindow(kst(8, 0))).isTrue();
        }
    }

    @Nested
    @DisplayName("인터벌 억제")
    class Interval {

        @Test
        @DisplayName("인터벌 안에 발송한 적이 있으면 막힌다")
        void withinInterval() {
            NotificationMessage m = message(NotificationType.WATCHER_REACTION, NEWER, null);

            assertThat(DispatchDecision.decide(m,
                    allow().withLastPushed(Map.of(m.suppressKey(), kst(11, 0))), kst(12, 0))
                    .suppressedReason()).isEqualTo(SuppressedReason.INTERVAL);
        }

        @Test
        @DisplayName("인터벌을 넘겼으면 보낸다")
        void beyondInterval() {
            NotificationMessage m = message(NotificationType.WATCHER_REACTION, NEWER, null);

            assertThat(DispatchDecision.decide(m,
                    allow().withLastPushed(Map.of(m.suppressKey(),
                            kst(12, 0).minusSeconds(25 * 3600))), kst(12, 0))
                    .shouldSend()).isTrue();
        }

        @Test
        @DisplayName("티어 경계는 1주다")
        void tierBoundaryIsOneWeek() {
            NotificationMessage m = message(NotificationType.TIER_BOUNDARY_NEAR, NEWER, null);

            assertThat(DispatchDecision.decide(m,
                    allow().withLastPushed(Map.of(m.suppressKey(),
                            kst(12, 0).minusSeconds(3 * 86400))), kst(12, 0))
                    .suppressedReason()).isEqualTo(SuppressedReason.INTERVAL);
        }

        @Test
        @DisplayName("억제 키가 없는 타입은 억제되지 않는다")
        void noSuppressKey() {
            NotificationMessage m = message(NotificationType.ACCOUNT_SANCTION, NEWER, null);
            assertThat(m.suppressKey()).isNull();
            assertThat(DispatchDecision.decide(m, allow(), kst(12, 0)).shouldSend()).isTrue();
        }
    }

    @Nested
    @DisplayName("활성 기기")
    class Devices {

        @Test
        @DisplayName("활성 기기가 없으면 보내지 않는다 — 알림함으로만 도달한다")
        void noDevice() {
            assertThat(DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, NEWER, null),
                    allow().withHasDevice(false), kst(12, 0)).suppressedReason())
                    .isEqualTo(SuppressedReason.NO_DEVICE);
        }

        @Test
        @DisplayName("기기 판정이 가장 마지막이다 — 앞 게이트에 걸리면 그 사유가 기록된다")
        void deviceGateIsLast() {
            UserNotificationSetting s = UserNotificationSetting.defaults(USER, kst(12, 0));
            s.applyMaster(false, kst(12, 0));

            assertThat(DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, NEWER, null),
                    allow().withSettings(s).withHasDevice(false), kst(12, 0)).suppressedReason())
                    .isEqualTo(SuppressedReason.MASTER_OFF);
        }
    }

    @Nested
    @DisplayName("공지 탭")
    class AnnouncementTab {

        @Test
        @DisplayName("공지 탭의 읽음 커서를 본다 — 알림 탭 커서와 섞이지 않는다")
        void usesAnnouncementCursor() {
            UserNotificationSetting s = UserNotificationSetting.defaults(USER, kst(12, 0));
            s.advanceReadCursor(NotificationTab.ANNOUNCEMENT, NEWER, kst(12, 0));
            DispatchInputs onlyAnnouncementRead = allow().withSettings(s);

            NotificationMessage announcement = new NotificationMessage(OLDER, USER,
                    NotificationType.ANNOUNCEMENT.name(), NotificationToggleGroup.NONE, null,
                    NotificationTab.ANNOUNCEMENT, "공지", "본문", null, null);
            assertThat(DispatchDecision.decide(announcement, onlyAnnouncementRead, kst(12, 0))
                    .suppressedReason()).isEqualTo(SuppressedReason.ALREADY_READ);

            // 공지를 읽었다고 알림 탭까지 읽음 처리되면 레드닷이 조용히 사라진다.
            assertThat(DispatchDecision.decide(
                    message(NotificationType.ACCOUNT_SANCTION, OLDER, null),
                    onlyAnnouncementRead, kst(12, 0)).shouldSend()).isTrue();
        }
    }
}
