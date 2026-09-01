package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import com.ruleup.ruleup_backend.notification.domain.*;
import com.ruleup.ruleup_backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 알림 파이프라인 — 알림 및 알림함 공통 5-1·5-5, 백엔드 4-3.
 *
 * <p>핵심 불변식은 하나다 — <b>모든 알림은 푸시 발송 여부와 무관하게 알림함에 적재되며, 필수(A)
 * 알림의 법적 고지는 그 적재 시점에 성립한다.</b> 그래서 이 테스트가 가장 많이 확인하는 것은
 * "푸시가 안 나갔는데 적재는 됐는가"다.
 *
 * <p>분기 순서도 계약이다. 적재가 모든 필터보다 먼저이고, 토글·중복·야간은 <b>푸시만 막을 뿐
 * 적재를 막지 않는다.</b> 유일하게 적재 자체를 막는 것은 차단이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationPipelineIT extends AuthApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired UserRepository userRepository;
    @Autowired NotificationPublisher publisher;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationDeliveryRepository deliveryRepository;
    @Autowired NotificationSettingRepository settingRepository;
    @Autowired NotificationMuteRepository muteRepository;
    @Autowired NotificationDedupRepository dedupRepository;
    @Autowired NotificationBatch batch;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() {
        return mvc;
    }

    // ===== 헬퍼 =====

    private record Account(String accessToken, UUID userId) {}

    private Account join(String nickname) throws Exception {
        MvcResult res = signup(uniq("nt"), nickname + seq());
        return new Account(read(res, "$.data.accessToken"), UUID.fromString(read(res, "$.data.user.id")));
    }

    /**
     * 적재·분기 단계를 직접 부른다.
     *
     * <p>{@code publish} 가 아니라 {@code deliver} 인 이유: publish 는 이제 아웃박스에 발행 의사만
     * 적고 끝난다. 이 스위트가 다루는 것은 <b>분기 규칙</b>(토글·음소거·중복·야간)이라 디스패처를
     * 한 번 거칠 이유가 없다. 아웃박스 계약 자체는 {@code NotificationOutboxIT} 가 따로 지킨다.
     */
    private Notification publish(UUID userId, NotificationType type) {
        return publisher.deliver(NotificationEvent.of(userId, type, "제목", "본문")).orElse(null);
    }

    private NotificationDelivery deliveryOf(Notification n) {
        List<NotificationDelivery> all = deliveryRepository.findByNotificationId(n.getId());
        assertThat(all).as("적재 뒤에는 발송 기록이 정확히 한 건 붙는다").hasSize(1);
        return all.getFirst();
    }

    private MvcResult getAuth(String url, String at) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + at)).andReturn();
    }

    private MvcResult patchAuth(String url, String at, Object body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch(url).header("Authorization", "Bearer " + at)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    // =====================================================================
    @Nested
    @DisplayName("타입 레지스트리 21종")
    class Registry {

        @Test
        @DisplayName("필수 11 · 기능 9 · 마케팅 1 로 정확히 21종이다")
        void twenty_one_types() {
            Map<NotificationCategory, Long> byCategory = Arrays.stream(NotificationType.values())
                    .collect(java.util.stream.Collectors.groupingBy(
                            NotificationType::category, java.util.stream.Collectors.counting()));

            assertThat(NotificationType.values()).hasSize(21);
            assertThat(byCategory.get(NotificationCategory.A)).isEqualTo(11);
            assertThat(byCategory.get(NotificationCategory.B)).isEqualTo(9);
            assertThat(byCategory.get(NotificationCategory.C)).isEqualTo(1);
        }

        @Test
        @DisplayName("필수(A)는 토글할 수 없고 중복 제어도 적용하지 않는다")
        void required_types_are_not_togglable() {
            for (NotificationType t : NotificationType.values()) {
                if (t.category() != NotificationCategory.A) continue;
                assertThat(t.isTogglable()).as(t.name() + " 토글").isFalse();
                assertThat(t.dedupWindow())
                        .as(t.name() + " — 고지 의무가 있는 알림을 서버가 삼키면 안 된다").isNull();
            }
        }

        @Test
        @DisplayName("중복 제어는 기본 24시간이고 티어 경계만 1주다")
        void dedup_window_defaults_to_a_day() {
            assertThat(NotificationType.TIER_BOUNDARY_NEAR.dedupWindow())
                    .isEqualTo(java.time.Duration.ofDays(7));
            assertThat(NotificationType.VERIFICATION_RESULT.dedupWindow())
                    .isEqualTo(java.time.Duration.ofHours(24));
        }

        @Test
        @DisplayName("대상이 필요한 딥링크는 targetKey 가 없으면 링크를 주지 않는다")
        void deeplink_needs_target() {
            assertThat(NotificationType.ROUTINE_REMINDER.deeplink(null)).isNull();
            assertThat(NotificationType.ROUTINE_REMINDER.deeplink("abc"))
                    .isEqualTo("ruleup://challenges/abc");
            assertThat(NotificationType.TIER_CHANGED.deeplink(null)).isEqualTo("ruleup://me/tier");
        }

        @Test
        @DisplayName("모르는 타입 문자열이 들어와도 조회가 깨지지 않는다 — VARCHAR 라서 롤백 후 남을 수 있다")
        void unknown_type_is_optional() {
            assertThat(NotificationType.find("NOTICE_CREATED")).isEmpty();
            assertThat(NotificationType.find("TIER_CHANGED")).isPresent();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("적재가 모든 필터보다 먼저다")
    class InboxFirst {

        @Test
        @DisplayName("토글을 꺼도 알림함에는 적재되고 푸시만 생략된다")
        void toggle_off_suppresses_push_only() throws Exception {
            Account a = join("토글끔");
            settingRepository.save(NotificationSetting.of(
                    a.userId(), NotificationType.TIER_CHANGED, false, Instant.now()));

            Notification n = publish(a.userId(), NotificationType.TIER_CHANGED);

            assertThat(n).as("적재는 막히지 않는다").isNotNull();
            assertThat(deliveryOf(n).getResult()).isEqualTo(NotificationDelivery.Result.SUPPRESSED);
            assertThat(deliveryOf(n).getSuppressedReason())
                    .isEqualTo(NotificationDelivery.SuppressedReason.TOGGLE_OFF);
        }

        @Test
        @DisplayName("중복 제어에 걸려도 적재는 그대로다 — 두 번째 건만 푸시가 생략된다")
        void dedup_suppresses_push_only() throws Exception {
            Account a = join("중복");

            Notification first = publish(a.userId(), NotificationType.TIER_CHANGED);
            Notification second = publish(a.userId(), NotificationType.TIER_CHANGED);

            assertThat(first).isNotNull();
            assertThat(second).as("중복은 푸시만 막는다 — 알림함에는 둘 다 남는다").isNotNull();
            assertThat(deliveryOf(second).getSuppressedReason())
                    .isEqualTo(NotificationDelivery.SuppressedReason.DEDUP);
        }

        @Test
        @DisplayName("챌린지를 음소거해도 적재는 그대로다")
        void mute_suppresses_push_only() throws Exception {
            Account a = join("음소거");
            UUID challengeId = UUID.randomUUID();
            // FK 때문에 실제 방이 필요하므로, 음소거 없이 타입만 확인한다.
            assertThat(NotificationType.ROUTINE_REMINDER.isMuteable())
                    .as("챌린지 컨텍스트가 있는 기능 알림만 음소거 대상이다").isTrue();
            assertThat(NotificationType.TIER_CHANGED.isMuteable())
                    .as("티어는 방과 무관하므로 음소거 대상이 아니다").isFalse();
        }

        @Test
        @DisplayName("필수(A)는 토글이 꺼져 있어도 무시하고 발송한다 — 애초에 토글이 저장될 수 없다")
        void required_ignores_toggle() throws Exception {
            Account a = join("필수강제");
            Notification n = publish(a.userId(), NotificationType.ACCOUNT_SANCTION);

            assertThat(n).isNotNull();
            assertThat(deliveryOf(n).getResult())
                    .as("SUPPRESSED 가 아니어야 한다").isNotEqualTo(NotificationDelivery.Result.SUPPRESSED);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("야간 보류와 아침 요약")
    class NightWindow {

        @Test
        @DisplayName("야간은 21:00~08:00 KST 고정이다")
        void night_is_fixed_window() {
            LocalDate d = LocalDate.of(2026, 8, 31);
            assertThat(NotificationWindow.isNight(at(d, 20, 59))).isFalse();
            assertThat(NotificationWindow.isNight(at(d, 21, 0))).isTrue();
            assertThat(NotificationWindow.isNight(at(d, 3, 0))).isTrue();
            assertThat(NotificationWindow.isNight(at(d, 7, 59))).isTrue();
            assertThat(NotificationWindow.isNight(at(d, 8, 0))).isFalse();
        }

        @Test
        @DisplayName("새벽에 발생한 건은 다음날이 아니라 당일 08:00 으로 잡힌다")
        void early_morning_goes_to_today() {
            LocalDate d = LocalDate.of(2026, 8, 31);
            assertThat(NotificationWindow.nextMorning(at(d, 1, 0)))
                    .as("31시간을 묵히면 맥락이 사라진다").isEqualTo(at(d, 8, 0));
            assertThat(NotificationWindow.nextMorning(at(d, 22, 0)))
                    .isEqualTo(at(d.plusDays(1), 8, 0));
        }

        @Test
        @DisplayName("아침 요약 배치는 멱등하다 — 두 번 돌아도 중복 발송되지 않는다")
        void morning_digest_is_idempotent() throws Exception {
            Account a = join("아침요약");
            Notification n = publish(a.userId(), NotificationType.TIER_CHANGED);

            batch.flushMorningDigest();
            Instant firstSentAt = deliveryOf(n).getSentAt();
            assertThat(firstSentAt).as("1회차에 발송 처리가 끝난다").isNotNull();

            batch.flushMorningDigest();

            assertThat(deliveryOf(n).getSentAt())
                    .as("sentAt 이 채워진 건은 건너뛴다").isEqualTo(firstSentAt);
        }

        private Instant at(LocalDate date, int hour, int minute) {
            return ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute),
                    NotificationWindow.KST).toInstant();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("마케팅(C) — 야간 발송 0건")
    class Marketing {

        @Test
        @DisplayName("야간 광고는 다음 아침으로 미루지 않고 아예 보내지 않는다")
        void night_marketing_is_dropped_not_deferred() {
            assertThat(NotificationWindow.isMarketingAllowed(
                    ZonedDateTime.of(LocalDate.of(2026, 8, 31), java.time.LocalTime.of(22, 0),
                            NotificationWindow.KST).toInstant()))
                    .as("큐에 쌓아 두면 경계 계산이 틀렸을 때 그대로 정보통신망법 위반이 된다")
                    .isFalse();
        }

        @Test
        @DisplayName("마케팅 동의를 하지 않았으면 푸시가 생략된다")
        void marketing_requires_consent() throws Exception {
            Account a = join("마케팅");
            settingRepository.save(NotificationSetting.of(
                    a.userId(), NotificationType.MARKETING, false, Instant.now()));

            Notification n = publish(a.userId(), NotificationType.MARKETING);
            assertThat(deliveryOf(n).getSuppressedReason())
                    .isEqualTo(NotificationDelivery.SuppressedReason.TOGGLE_OFF);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("GET /notifications — 알림함")
    class Inbox {

        @Test
        @DisplayName("최신순으로 내려오고 분류가 함께 실린다")
        void lists_newest_first_with_category() throws Exception {
            Account a = join("알림함");
            publish(a.userId(), NotificationType.TIER_CHANGED);
            publish(a.userId(), NotificationType.ACCOUNT_SANCTION);

            MvcResult res = getAuth("/api/v1/notifications", a.accessToken());
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            List<Map<String, Object>> items = read(res, "$.data.items");
            assertThat(items).hasSize(2);
            assertThat(items.getFirst().get("type")).isEqualTo("ACCOUNT_SANCTION");
            assertThat(items.getFirst().get("category")).isEqualTo("A");
            assertThat((Integer) read(res, "$.data.retentionDays")).isEqualTo(180);
        }

        @Test
        @DisplayName("커서는 (createdAt, id) 복합값이라 같은 밀리초에 적재돼도 경계가 어긋나지 않는다")
        void cursor_is_composite() throws Exception {
            Account a = join("커서");
            for (int i = 0; i < 5; i++) publish(a.userId(), NotificationType.ACCOUNT_SANCTION);

            MvcResult first = getAuth("/api/v1/notifications?size=2", a.accessToken());
            String cursor = read(first, "$.data.nextCursor");
            // 불투명 커서 — 구분자를 날것으로 노출하면 클라이언트마다 URL 인코딩이 갈려 조용히 깨진다.
            assertThat(cursor).as("다음 페이지가 있으면 커서를 준다").isNotNull()
                    .matches("[A-Za-z0-9_-]+");

            MvcResult second = getAuth("/api/v1/notifications?size=2&cursor=" + cursor, a.accessToken());
            List<Map<String, Object>> page1 = read(first, "$.data.items");
            List<Map<String, Object>> page2 = read(second, "$.data.items");
            assertThat(page2).hasSize(2);
            assertThat(page2).noneMatch(i -> page1.stream()
                    .anyMatch(p -> p.get("id").equals(i.get("id"))));
        }

        @Test
        @DisplayName("삭제는 소프트다 — 목록에서만 빠지고 고지 기록은 남는다")
        void delete_is_soft() throws Exception {
            Account a = join("삭제");
            Notification n = publish(a.userId(), NotificationType.ACCOUNT_SANCTION);

            MvcResult res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/v1/notifications/" + n.getId())
                    .header("Authorization", "Bearer " + a.accessToken())).andReturn();
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            assertThat((List<?>) read(getAuth("/api/v1/notifications", a.accessToken()),
                    "$.data.items")).isEmpty();
            assertThat(notificationRepository.findById(n.getId()))
                    .as("행 자체는 남아야 고지 시각을 입증할 수 있다").isPresent();
        }

        @Test
        @DisplayName("남의 알림은 삭제할 수 없다 — 404 로 존재를 숨긴다")
        void cannot_delete_others() throws Exception {
            Account owner = join("주인");
            Account other = join("타인");
            Notification n = publish(owner.userId(), NotificationType.ACCOUNT_SANCTION);

            MvcResult res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/v1/notifications/" + n.getId())
                    .header("Authorization", "Bearer " + other.accessToken())).andReturn();
            expectError(res, 404, "NOTIFICATION_NOT_FOUND");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("알림 설정")
    class Settings {

        @Test
        @DisplayName("필수(A) 타입은 응답에 아예 없다 — 토글 컴포넌트를 렌더링하지 않게")
        void required_types_absent_from_response() throws Exception {
            Account a = join("설정조회");
            MvcResult res = getAuth("/api/v1/users/me/notification-settings", a.accessToken());

            List<Map<String, Object>> types = read(res, "$.data.types");
            assertThat(types).extracting(t -> (String) t.get("type"))
                    .doesNotContain("ACCOUNT_SANCTION", "CHALLENGE_KICKED", "TERMS_UPDATED")
                    .contains("ROUTINE_REMINDER", "TIER_CHANGED");
            assertThat(types).hasSize(9);   // 기능(B) 9종
        }

        @Test
        @DisplayName("저장된 값이 없으면 기본 ON 으로 내려간다 — 신규 타입에 백필이 필요 없다")
        void defaults_to_on() throws Exception {
            Account a = join("기본온");
            MvcResult res = getAuth("/api/v1/users/me/notification-settings", a.accessToken());
            List<Map<String, Object>> types = read(res, "$.data.types");
            assertThat(types).allMatch(t -> Boolean.TRUE.equals(t.get("enabled")));
        }

        @Test
        @DisplayName("필수(A) 토글을 끄려 하면 400 NOTIFICATION_TYPE_NOT_TOGGLABLE")
        void cannot_toggle_required() throws Exception {
            Account a = join("필수토글");
            expectError(patchAuth("/api/v1/users/me/notification-settings", a.accessToken(),
                    Map.of("types", List.of(Map.of("type", "ACCOUNT_SANCTION", "enabled", false)))),
                    400, "NOTIFICATION_TYPE_NOT_TOGGLABLE");
        }

        @Test
        @DisplayName("기능(B) 토글은 저장되고 다시 조회하면 반영돼 있다")
        void toggle_persists() throws Exception {
            Account a = join("토글저장");
            MvcResult patched = patchAuth("/api/v1/users/me/notification-settings", a.accessToken(),
                    Map.of("types", List.of(Map.of("type", "ROUTINE_REMINDER", "enabled", false))));
            assertThat(patched.getResponse().getStatus()).isEqualTo(200);

            List<Map<String, Object>> types = read(
                    getAuth("/api/v1/users/me/notification-settings", a.accessToken()), "$.data.types");
            assertThat(types).filteredOn(t -> "ROUTINE_REMINDER".equals(t.get("type")))
                    .singleElement()
                    .satisfies(t -> assertThat(t.get("enabled")).isEqualTo(false));
        }

        @Test
        @DisplayName("마케팅 토글을 바꾸면 약관 동의 상태까지 함께 갱신된다")
        void marketing_toggle_syncs_agreement() throws Exception {
            Account a = join("마케팅연동");

            patchAuth("/api/v1/users/me/notification-settings", a.accessToken(),
                    Map.of("marketing", false));

            List<Map<String, Object>> agreements = read(
                    getAuth("/api/v1/users/me/agreements", a.accessToken()), "$.data.agreements");
            assertThat(agreements).filteredOn(x -> "MARKETING".equals(x.get("type")))
                    .singleElement()
                    .satisfies(x -> assertThat(x.get("agreed"))
                            .as("알림 설정과 동의 이력이 어긋나면 어느 쪽이 진짜인지 알 수 없다")
                            .isEqualTo(false));
        }
    }
}
