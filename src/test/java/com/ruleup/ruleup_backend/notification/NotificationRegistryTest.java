package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationTab;
import com.ruleup.ruleup_backend.notification.domain.NotificationToggleGroup;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 레지스트리 계약 — 백엔드 테크 스펙 5절, 공통 8절.
 *
 * <p>레지스트리는 <b>테이블이 아니라 코드 enum</b>이다(9/8 결정). 배포 없이 바꿀 수 없다는 뜻이라,
 * 값이 맞는지 지켜 주는 것은 이 테스트뿐이다. 그래서 23종 전부를 이름으로 못 박는다 —
 * 「전부 순회해서 null 아님」 같은 헐거운 검사는 오타 하나를 그대로 통과시킨다.
 *
 * <p>스프링을 띄우지 않는다. 레지스트리는 순수 함수 집합이고, 컨테이너를 붙이면 이 계약이
 * 깨졌을 때 컨텍스트 로딩 실패에 묻힌다.
 */
class NotificationRegistryTest {

    @Nested
    @DisplayName("타입 집합")
    class Types {

        @Test
        @DisplayName("23종이며 이름이 스펙 표와 정확히 일치한다")
        void twentyThreeTypes() {
            assertThat(Arrays.stream(NotificationType.values()).map(Enum::name))
                    .containsExactlyInAnyOrder(
                            "CHALLENGE_KICKED", "ACCOUNT_SANCTION", "DORMANCY_NOTICE",
                            "INACTIVE_WITHDRAWAL_NOTICE", "MODERATION_REJECTED",
                            "CHALLENGE_IMAGE_REMOVED", "PERMISSION_REGRANT_REQUIRED",
                            "CHEAT_DETECTED", "APPEAL_RESULT", "TERMS_UPDATED",
                            "DEVICE_LOGGED_OUT", "CS_ANSWERED",
                            "VERIFICATION_RESULT", "CONSECUTIVE_FAILURE_WARNING",
                            "CHALLENGE_LIFECYCLE", "WATCHER_INVITATION_EXPIRED", "TIER_CHANGED",
                            "TIER_BOUNDARY_NEAR", "PENALTY_FAILURE_SHARED", "WATCHER_REACTION",
                            "ROUTINE_REMINDER", "MARKETING", "ANNOUNCEMENT");
        }

        @Test
        @DisplayName("모르는 값은 empty — 롤백 후 남은 행 때문에 알림함이 깨지면 안 된다")
        void unknownTypeIsEmpty() {
            assertThat(NotificationType.find("INACTIVITY_WITHDRAWAL")).isEmpty();
            assertThat(NotificationType.find(null)).isEmpty();
            assertThat(NotificationType.find("CHALLENGE_KICKED"))
                    .contains(NotificationType.CHALLENGE_KICKED);
        }
    }

    @Nested
    @DisplayName("토글 그룹 — 발송 판정의 입력이자 감사 스냅샷")
    class ToggleGroups {

        @Test
        @DisplayName("계정 12종")
        void account() {
            assertThat(byGroup(NotificationToggleGroup.ACCOUNT)).containsExactlyInAnyOrder(
                    NotificationType.CHALLENGE_KICKED, NotificationType.ACCOUNT_SANCTION,
                    NotificationType.DORMANCY_NOTICE, NotificationType.INACTIVE_WITHDRAWAL_NOTICE,
                    NotificationType.MODERATION_REJECTED, NotificationType.CHALLENGE_IMAGE_REMOVED,
                    NotificationType.PERMISSION_REGRANT_REQUIRED, NotificationType.CHEAT_DETECTED,
                    NotificationType.APPEAL_RESULT, NotificationType.TERMS_UPDATED,
                    NotificationType.DEVICE_LOGGED_OUT, NotificationType.CS_ANSWERED);
        }

        @Test
        @DisplayName("챌린지 8종")
        void challenge() {
            assertThat(byGroup(NotificationToggleGroup.CHALLENGE)).containsExactlyInAnyOrder(
                    NotificationType.VERIFICATION_RESULT,
                    NotificationType.CONSECUTIVE_FAILURE_WARNING,
                    NotificationType.CHALLENGE_LIFECYCLE,
                    NotificationType.WATCHER_INVITATION_EXPIRED, NotificationType.TIER_CHANGED,
                    NotificationType.TIER_BOUNDARY_NEAR, NotificationType.PENALTY_FAILURE_SHARED,
                    NotificationType.WATCHER_REACTION);
        }

        @Test
        @DisplayName("마케팅 1종 · 그룹 없음(NONE) 2종 — 리마인더는 상시, 공지는 탭이 다르다")
        void marketingAndNone() {
            assertThat(byGroup(NotificationToggleGroup.MARKETING))
                    .containsExactly(NotificationType.MARKETING);
            assertThat(byGroup(NotificationToggleGroup.NONE)).containsExactlyInAnyOrder(
                    NotificationType.ROUTINE_REMINDER, NotificationType.ANNOUNCEMENT);
        }

        @Test
        @DisplayName("그룹 토글이 없는 타입은 그룹 판정을 통과한다 — 리마인더는 마스터·음소거만 본다")
        void noneGroupPassesGroupGate() {
            assertThat(NotificationToggleGroup.NONE.isTogglable()).isFalse();
            assertThat(NotificationToggleGroup.ACCOUNT.isTogglable()).isTrue();
            assertThat(NotificationToggleGroup.CHALLENGE.isTogglable()).isTrue();
            assertThat(NotificationToggleGroup.MARKETING.isTogglable()).isTrue();
        }

        private Set<NotificationType> byGroup(NotificationToggleGroup group) {
            return Arrays.stream(NotificationType.values())
                    .filter(t -> t.toggleGroup() == group)
                    .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        }
    }

    @Nested
    @DisplayName("탭과 푸시 대상")
    class TabAndPushable {

        @Test
        @DisplayName("공지만 운영자 공지 탭이고, 나머지는 전부 알림 탭이다")
        void tabs() {
            assertThat(NotificationType.ANNOUNCEMENT.tab()).isEqualTo(NotificationTab.ANNOUNCEMENT);
            assertThat(NotificationTab.ANNOUNCEMENT.code()).isEqualTo((byte) 1);
            assertThat(NotificationTab.NOTIFICATION.code()).isEqualTo((byte) 0);

            assertThat(Arrays.stream(NotificationType.values())
                    .filter(t -> t.tab() == NotificationTab.ANNOUNCEMENT))
                    .containsExactly(NotificationType.ANNOUNCEMENT);
        }

        @Test
        @DisplayName("공지만 푸시 대상이 아니다 — 켜면 2만 명에게 나가므로 운영 토글로 두지 않는다")
        void announcementIsNotPushable() {
            assertThat(NotificationType.ANNOUNCEMENT.isPushable()).isFalse();
            assertThat(Arrays.stream(NotificationType.values()).filter(t -> !t.isPushable()))
                    .containsExactly(NotificationType.ANNOUNCEMENT);
        }
    }

    @Nested
    @DisplayName("딥링크 — 공통 8절 표와 1:1")
    class Deeplinks {

        @Test
        @DisplayName("계정 그룹")
        void account() {
            assertThat(link(NotificationType.CHALLENGE_KICKED)).isEqualTo("ruleup://me/sanctions");
            assertThat(link(NotificationType.ACCOUNT_SANCTION)).isEqualTo("ruleup://me/sanctions");
            assertThat(link(NotificationType.DORMANCY_NOTICE)).isEqualTo("ruleup://home");
            assertThat(link(NotificationType.INACTIVE_WITHDRAWAL_NOTICE)).isEqualTo("ruleup://home");
            assertThat(link(NotificationType.MODERATION_REJECTED)).isEqualTo("ruleup://profile/edit");
            assertThat(link(NotificationType.CHALLENGE_IMAGE_REMOVED))
                    .isEqualTo("ruleup://challenges/" + CHALLENGE + "/edit");
            assertThat(link(NotificationType.PERMISSION_REGRANT_REQUIRED))
                    .isEqualTo("ruleup://challenges/" + CHALLENGE + "/setup");
            assertThat(link(NotificationType.APPEAL_RESULT)).isEqualTo("ruleup://me/appeals");
            assertThat(link(NotificationType.TERMS_UPDATED)).isEqualTo("ruleup://settings/agreements");
        }

        @Test
        @DisplayName("부정행위는 me/cheat-history 가 확정값이나 받침 화면이 없어 me/sanctions 를 보낸다 — 공통 #18")
        void cheatDetectedStaysOnSanctions() {
            assertThat(link(NotificationType.CHEAT_DETECTED)).isEqualTo("ruleup://me/sanctions");
        }

        @Test
        @DisplayName("기기 로그아웃은 딥링크가 없다 — 그 기기는 이미 로그아웃 상태다")
        void deviceLoggedOutHasNoLink() {
            assertThat(link(NotificationType.DEVICE_LOGGED_OUT)).isNull();
        }

        @Test
        @DisplayName("챌린지 그룹")
        void challenge() {
            assertThat(link(NotificationType.VERIFICATION_RESULT))
                    .isEqualTo("ruleup://challenges/" + CHALLENGE);
            assertThat(link(NotificationType.CONSECUTIVE_FAILURE_WARNING))
                    .isEqualTo("ruleup://challenges/" + CHALLENGE);
            assertThat(link(NotificationType.CHALLENGE_LIFECYCLE))
                    .isEqualTo("ruleup://challenges/" + CHALLENGE);
            assertThat(link(NotificationType.ROUTINE_REMINDER))
                    .isEqualTo("ruleup://challenges/" + CHALLENGE);
            assertThat(link(NotificationType.WATCHER_INVITATION_EXPIRED))
                    .isEqualTo("ruleup://challenges/" + CHALLENGE + "/watchers");
            assertThat(link(NotificationType.TIER_CHANGED)).isEqualTo("ruleup://me/tier");
            assertThat(link(NotificationType.TIER_BOUNDARY_NEAR)).isEqualTo("ruleup://me/tier");
        }

        @Test
        @DisplayName("감시자 통지는 방이 아니라 수신 관리로 간다 — 감시자는 방 멤버가 아니다")
        void watcherNoticeGoesToWatchingScreen() {
            assertThat(link(NotificationType.PENALTY_FAILURE_SHARED))
                    .isEqualTo("ruleup://watching/notices/" + NOTICE);
        }

        @Test
        @DisplayName("응원 반응은 실패 당사자에게 가므로 자기 기록 화면이다")
        void reactionGoesToOwnCalendar() {
            assertThat(link(NotificationType.WATCHER_REACTION)).isEqualTo("ruleup://me/calendar");
        }

        @Test
        @DisplayName("마케팅·공지는 레지스트리 기본값이 null 이고 발행부가 정한다")
        void nullByDefault() {
            assertThat(link(NotificationType.MARKETING)).isNull();
            assertThat(link(NotificationType.ANNOUNCEMENT)).isNull();
        }

        @Test
        @DisplayName("치환할 파라미터가 없으면 링크를 주지 않는다 — 깨진 경로로 보내느니 알림함 폴백이 낫다")
        void missingParamYieldsNoLink() {
            assertThat(NotificationType.VERIFICATION_RESULT.deeplink(Map.of())).isNull();
            assertThat(NotificationType.VERIFICATION_RESULT.deeplink(
                    Map.of(NotificationParams.CHALLENGE_ID, " "))).isNull();
        }

        @Test
        @DisplayName("딥링크는 전부 커스텀 스킴이다 — https 앱링크는 쓰지 않는다")
        void allCustomScheme() {
            for (NotificationType t : NotificationType.values()) {
                String l = link(t);
                if (l != null) assertThat(l).as(t.name()).startsWith("ruleup://");
            }
        }

        private static final String CHALLENGE = "c-301";
        private static final String NOTICE = "n-77";

        private String link(NotificationType type) {
            return type.deeplink(Map.of(
                    NotificationParams.CHALLENGE_ID, CHALLENGE,
                    NotificationParams.NOTICE_ID, NOTICE));
        }
    }

    @Nested
    @DisplayName("억제 인터벌 — 6종만 쓴다")
    class SuppressIntervals {

        @Test
        @DisplayName("억제를 쓰는 타입은 정확히 6종이다")
        void exactlySix() {
            assertThat(Arrays.stream(NotificationType.values())
                    .filter(t -> t.suppressInterval() != null))
                    .containsExactlyInAnyOrder(
                            NotificationType.PERMISSION_REGRANT_REQUIRED,
                            NotificationType.CONSECUTIVE_FAILURE_WARNING,
                            NotificationType.TIER_BOUNDARY_NEAR,
                            NotificationType.PENALTY_FAILURE_SHARED,
                            NotificationType.WATCHER_REACTION,
                            NotificationType.MARKETING);
        }

        @Test
        @DisplayName("티어 경계만 1주, 나머지는 24시간")
        void values() {
            assertThat(NotificationType.TIER_BOUNDARY_NEAR.suppressInterval())
                    .isEqualTo(Duration.ofDays(7));
            for (NotificationType t : new NotificationType[]{
                    NotificationType.PERMISSION_REGRANT_REQUIRED,
                    NotificationType.CONSECUTIVE_FAILURE_WARNING,
                    NotificationType.PENALTY_FAILURE_SHARED,
                    NotificationType.WATCHER_REACTION,
                    NotificationType.MARKETING}) {
                assertThat(t.suppressInterval()).as(t.name()).isEqualTo(Duration.ofHours(24));
            }
        }

        @Test
        @DisplayName("억제를 안 쓰는 타입은 suppressKey 가 null 이다 — 인덱스에 빈 엔트리를 늘리지 않는다")
        void nonSuppressedTypesHaveNoKey() {
            assertThat(NotificationType.CHALLENGE_KICKED.suppressKey(params())).isNull();
            assertThat(NotificationType.VERIFICATION_RESULT.suppressKey(params())).isNull();
        }
    }

    @Nested
    @DisplayName("dedup_key — 발행 멱등(1회성). UNIQUE 가 INSERT 단계에서 막는다")
    class DedupKeys {

        private final UUID user = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        @Test
        @DisplayName("전 타입이 dedup_key 를 가진다 — 발행 재시도를 막는 것은 전 타입 공통이다")
        void everyTypeHasDedupKey() {
            for (NotificationType t : NotificationType.values()) {
                assertThat(t.dedupKey(user, params())).as(t.name()).isNotBlank();
            }
        }

        @Test
        @DisplayName("{TYPE}:{userId}:{식별자} 형식이며 유저가 다르면 키도 다르다")
        void shape() {
            assertThat(NotificationType.APPEAL_RESULT.dedupKey(user, params()))
                    .isEqualTo("APPEAL_RESULT:" + user + ":ap-9");

            UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
            assertThat(NotificationType.APPEAL_RESULT.dedupKey(other, params()))
                    .isNotEqualTo(NotificationType.APPEAL_RESULT.dedupKey(user, params()));
        }

        @Test
        @DisplayName("타입별 식별자 구성이 스펙 표와 일치한다")
        void perTypeIdentifiers() {
            assertThat(NotificationType.VERIFICATION_RESULT.dedupKey(user, params()))
                    .endsWith(":v-12");
            assertThat(NotificationType.CHALLENGE_LIFECYCLE.dedupKey(user, params()))
                    .endsWith(":c-301:STARTED");
            assertThat(NotificationType.WATCHER_INVITATION_EXPIRED.dedupKey(user, params()))
                    .endsWith(":c-301:w-5");
            assertThat(NotificationType.ROUTINE_REMINDER.dedupKey(user, params()))
                    .endsWith(":2026-09-08:MORNING");
            // 공지는 announcement_id · user_id 로 행별 멱등 — user_id 는 표준 형식이 이미 담고 있다.
            assertThat(NotificationType.ANNOUNCEMENT.dedupKey(user, params()))
                    .isEqualTo("ANNOUNCEMENT:" + user + ":an-3");
        }

        @Test
        @DisplayName("티어 알림의 키에 challenge_id 가 들어가지 않는다 — 3개 방 참여자가 3번 받는다")
        void tierKeysNeverCarryChallenge() {
            String changed = NotificationType.TIER_CHANGED.dedupKey(user, params());
            String near = NotificationType.TIER_BOUNDARY_NEAR.suppressKey(params());
            assertThat(changed).doesNotContain("c-301");
            assertThat(near).doesNotContain("c-301").isEqualTo("TIER_BOUNDARY_NEAR:UP");
        }

        @Test
        @DisplayName("같은 방향의 티어 변동이 두 번 일어나도 두 번 적재된다 — UNIQUE 가 평생 막으면 규칙 1 위반")
        void tierChangeIsNotBlockedForever() {
            Map<String, String> first = params();
            Map<String, String> second = params();
            second.put(NotificationParams.EVENT_KEY, "tier-change-2");

            assertThat(NotificationType.TIER_CHANGED.dedupKey(user, first))
                    .isNotEqualTo(NotificationType.TIER_CHANGED.dedupKey(user, second));
        }

        @Test
        @DisplayName("재심사 거부·이미지 삭제도 두 번째 발생을 막지 않는다")
        void repeatableAccountNoticesAreNotBlockedForever() {
            Map<String, String> second = params();
            second.put(NotificationParams.EVENT_KEY, "mod-2");

            assertThat(NotificationType.MODERATION_REJECTED.dedupKey(user, params()))
                    .isNotEqualTo(NotificationType.MODERATION_REJECTED.dedupKey(user, second));
            assertThat(NotificationType.CHALLENGE_IMAGE_REMOVED.dedupKey(user, params()))
                    .isNotEqualTo(NotificationType.CHALLENGE_IMAGE_REMOVED.dedupKey(user, second));
        }

        @Test
        @DisplayName("키 파라미터가 비면 키가 없다 — 예외를 던지면 알림 버그가 도메인 판정을 롤백시킨다")
        void missingParamYieldsNoKey() {
            assertThat(NotificationType.APPEAL_RESULT.dedupKey(user, Map.of())).isNull();
            assertThat(NotificationType.MARKETING.suppressKey(Map.of())).isNull();
        }

        @Test
        @DisplayName("빈 값을 조용히 이어 붙이지 않는다 — 다른 사건이 같은 키를 가지면 적재가 삼켜진다")
        void blankValueNeverBecomesPartOfAKey() {
            Map<String, String> blank = params();
            blank.put(NotificationParams.APPEAL_ID, "  ");
            assertThat(NotificationType.APPEAL_RESULT.dedupKey(user, blank)).isNull();
        }

        @Test
        @DisplayName("dedup_key 는 컬럼 길이 160자를 넘지 않는다")
        void fitsColumn() {
            Map<String, String> longish = params();
            longish.replaceAll((k, v) -> UUID.randomUUID().toString());
            for (NotificationType t : NotificationType.values()) {
                assertThat(t.dedupKey(user, longish)).as(t.name()).hasSizeLessThanOrEqualTo(160);
                String s = t.suppressKey(longish);
                if (s != null) assertThat(s).as(t.name()).hasSizeLessThanOrEqualTo(160);
            }
        }
    }

    @Nested
    @DisplayName("suppress_key — 인터벌 억제(반복성). 같은 키가 여러 행에 반복해 붙는다")
    class SuppressKeys {

        @Test
        @DisplayName("타입별 키 구성이 스펙 표와 일치한다")
        void shapes() {
            assertThat(NotificationType.PERMISSION_REGRANT_REQUIRED.suppressKey(params()))
                    .isEqualTo("PERMISSION_REGRANT_REQUIRED:CAMERA:c-301");
            assertThat(NotificationType.CONSECUTIVE_FAILURE_WARNING.suppressKey(params()))
                    .isEqualTo("CONSECUTIVE_FAILURE_WARNING:c-301:r-7");
            assertThat(NotificationType.PENALTY_FAILURE_SHARED.suppressKey(params()))
                    .isEqualTo("PENALTY_FAILURE_SHARED:c-301:r-7:u-2");
            assertThat(NotificationType.WATCHER_REACTION.suppressKey(params()))
                    .isEqualTo("WATCHER_REACTION:c-301:s-4");
            assertThat(NotificationType.MARKETING.suppressKey(params()))
                    .isEqualTo("MARKETING:cp-1");
        }

        @Test
        @DisplayName("권한 재허용 키에 challenge_id 가 들어간다 — 없으면 한쪽 방이 미해소로 강퇴된다")
        void permissionKeyIsPerChallenge() {
            Map<String, String> otherRoom = params();
            otherRoom.put(NotificationParams.CHALLENGE_ID, "c-999");

            assertThat(NotificationType.PERMISSION_REGRANT_REQUIRED.suppressKey(params()))
                    .isNotEqualTo(NotificationType.PERMISSION_REGRANT_REQUIRED.suppressKey(otherRoom));
        }
    }

    /** 모든 파라미터를 채운 사전. 각 타입은 자기가 선언한 것만 골라 쓴다. */
    private static Map<String, String> params() {
        Map<String, String> p = new HashMap<>();
        p.put(NotificationParams.EVENT_KEY, "evt-1");
        p.put(NotificationParams.CHALLENGE_ID, "c-301");
        p.put(NotificationParams.ROUTINE_ID, "r-7");
        p.put(NotificationParams.TARGET_USER_ID, "u-2");
        p.put(NotificationParams.SENDER_ID, "s-4");
        p.put(NotificationParams.NOTICE_ID, "n-77");
        p.put(NotificationParams.WATCHER_ID, "w-5");
        p.put(NotificationParams.PERMISSION, "CAMERA");
        p.put(NotificationParams.DIRECTION, "UP");
        p.put(NotificationParams.PHASE, "STARTED");
        p.put(NotificationParams.APPEAL_ID, "ap-9");
        p.put(NotificationParams.VERIFICATION_ID, "v-12");
        p.put(NotificationParams.TARGET_KEY, "nickname");
        p.put(NotificationParams.ANNOUNCEMENT_ID, "an-3");
        p.put(NotificationParams.INQUIRY_ID, "iq-8");
        p.put(NotificationParams.CAMPAIGN_ID, "cp-1");
        p.put(NotificationParams.DATE, "2026-09-08");
        p.put(NotificationParams.SLOT, "MORNING");
        return p;
    }
}
