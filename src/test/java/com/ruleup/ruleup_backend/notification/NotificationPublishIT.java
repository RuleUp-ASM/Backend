package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.repository.NotificationSettingRepository;
import com.ruleup.ruleup_backend.notification.repository.NotificationRepository;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationTab;
import com.ruleup.ruleup_backend.notification.domain.NotificationTemplate;
import com.ruleup.ruleup_backend.notification.domain.NotificationToggleGroup;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.domain.UserNotificationSetting;
import com.ruleup.ruleup_backend.notification.queue.NotificationMessage;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적재 계약 — <b>적재가 곧 고지 성립이고, 도메인 커밋과 원자적이다</b>(백엔드 4-1).
 *
 * <p>재설계로 아웃박스가 사라졌다. 적재를 도메인 트랜잭션 안으로 옮기면 아웃박스가 풀던 문제가
 * 통째로 없어지기 때문이다 — 도메인이 롤백되면 INSERT 도 함께 롤백되고, 커밋됐으면 적재도
 * 커밋돼 있다. 「릴레이 5회 실패 = 적재 누락」이라는 <b>실패 모드를 스스로 만들던</b> 구조를 버렸다.
 *
 * <p>그래서 이 스위트가 확인하는 것은 두 가지다. ① 적재가 도메인 커밋과 붙어 있는가
 * ② <b>적재 경로에 조건 분기가 하나도 없는가</b> — 토글·음소거·야간·차단 무엇도 적재를 막지 않는다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class NotificationPublishIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired NotificationPublisher publisher;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationSettingRepository settingRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired NotificationTestQueue.Recording queue;

    private NotificationTestQueue.Recording spy() {
        return queue;
    }

    @BeforeEach
    void reset() {
        queue.reset();
    }

    /** {@code nickname} 은 VARCHAR(12) 이고 스위트가 DB 를 공유한다 — 짧고 겹치지 않아야 한다. */
    private static String nickname() {
        return "%s%s".formatted("p", Long.toString(System.nanoTime(), 36));
    }

    private UUID newUser() {
        String tag = "pub" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com", nickname(), null, List.of())).getId();
    }

    /**
     * 아무 타입이나 하나 발행하기 위한 이벤트. 문구가 레지스트리로 옮겨졌으므로 <b>그 타입이
     * 요구하는 변형과 치환 값까지</b> 채운다 — 비면 폴백 문구로 적재돼 검증이 무뎌진다.
     */
    private NotificationEvent event(UUID userId, NotificationType type) {
        Map<String, String> params = new java.util.HashMap<>(Map.of(
                NotificationParams.EVENT_KEY, "e" + SEQ.incrementAndGet(),
                NotificationParams.APPEAL_ID, "ap" + SEQ.get(),
                NotificationParams.VERIFICATION_ID, "v" + SEQ.get(),
                NotificationParams.DIRECTION, "UP",
                NotificationParams.ANNOUNCEMENT_ID, "an" + SEQ.get(),
                NotificationParams.ACTOR_NAME, "테스터",
                NotificationParams.CHALLENGE_TITLE, "테스트 방",
                NotificationParams.ROUTINE_NAME, "아침 러닝",
                NotificationParams.REASON, "테스트 사유"));
        // 변형이 있는 타입은 첫 변형으로 렌더한다 — 어느 것이든 문구가 나오기만 하면 된다.
        NotificationTemplate.of(type).stream().findFirst()
                .ifPresent(t -> params.putAll(t.variantParams()));
        return NotificationEvent.of(userId, type, params);
    }

    private List<Notification> inbox(UUID userId) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(userId)).toList();
    }

    // =====================================================================
    @Nested
    @DisplayName("큐 설정이 없을 때")
    class WithoutQueueConfig {

        @Test
        @DisplayName("SQS 클라이언트를 아예 만들지 않는다 — 리전 없는 환경에서 컨텍스트가 죽으면 안 된다")
        void noSqsClientWhenUrlIsBlank() {
            // yaml 의 ${NOTIFICATION_QUEUE_URL:} 기본값은 **빈 문자열**이라 속성 자체는 존재한다.
            // @ConditionalOnProperty 로 걸면 그것도 존재로 보고 클라이언트를 만들려다,
            // 리전을 못 찾는 CI·로컬에서 기동이 통째로 실패한다.
            assertThat(applicationContext.getBeanNamesForType(
                    software.amazon.awssdk.services.sqs.SqsClient.class))
                    .as("큐 URL 이 비면 AWS SDK 를 건드리지 않는다").isEmpty();

            // 컨슈머도 같은 조건이어야 한다. 조건이 어긋나면 이 빈만 살아나 SqsClient 를
            // 못 찾고 컨텍스트가 죽는다 — 실제로 그렇게 CI 가 두 번 깨졌다.
            assertThat(applicationContext.getBeanNamesForType(
                    com.ruleup.ruleup_backend.notification.consumer.NotificationConsumer.class))
                    .as("프로듀서와 컨슈머는 같은 조건으로 배선된다").isEmpty();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("도메인 커밋과 원자적이다")
    class Atomicity {

        @Test
        @DisplayName("도메인이 롤백되면 고지도 함께 사라진다 — 제재 없는 제재 알림이 남지 않는다")
        void rollbackTakesTheNoticeWithIt() {
            UUID userId = newUser();
            try {
                txTemplate.execute(status -> {
                    publisher.publish(event(userId, NotificationType.ACCOUNT_SANCTION));
                    throw new IllegalStateException("도메인 실패");
                });
            } catch (IllegalStateException expected) {
                // 도메인 트랜잭션이 터진 상황을 만든 것이다
            }
            assertThat(inbox(userId)).isEmpty();
        }

        @Test
        @DisplayName("커밋되면 적재가 이미 끝나 있다 — 릴레이·스윕을 기다리지 않는다")
        void commitMeansStored() {
            UUID userId = newUser();
            txTemplate.executeWithoutResult(s ->
                    publisher.publish(event(userId, NotificationType.ACCOUNT_SANCTION)));

            assertThat(inbox(userId)).hasSize(1);
        }

        @Test
        @DisplayName("enqueue 는 커밋 이후다 — 롤백된 알림의 푸시가 잠금화면에 뜨면 되돌릴 수 없다")
        void enqueueHappensAfterCommit() {
            UUID userId = newUser();
            try {
                txTemplate.execute(status -> {
                    publisher.publish(event(userId, NotificationType.ACCOUNT_SANCTION));
                    assertThat(spy().sent).as("커밋 전에는 큐에 들어가지 않는다").isEmpty();
                    throw new IllegalStateException("도메인 실패");
                });
            } catch (IllegalStateException expected) {
                // 의도된 롤백
            }
            assertThat(spy().sent).as("롤백됐으면 끝내 들어가지 않는다").isEmpty();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("적재 경로에 조건 분기가 하나도 없다 — 절대 규칙 1")
    class NoFiltersOnStore {

        @Test
        @DisplayName("마스터 토글을 꺼도 적재된다")
        void masterOffStillStores() {
            UUID userId = newUser();
            UserNotificationSetting s = UserNotificationSetting.defaults(userId, Instant.now());
            s.applyMaster(false, Instant.now());
            settingRepository.save(s);

            txTemplate.executeWithoutResult(t ->
                    publisher.publish(event(userId, NotificationType.TIER_CHANGED)));

            assertThat(inbox(userId)).hasSize(1);
        }

        @Test
        @DisplayName("그룹 토글을 꺼도 적재된다 — 막히는 것은 푸시뿐이다")
        void groupOffStillStores() {
            UUID userId = newUser();
            UserNotificationSetting s = UserNotificationSetting.defaults(userId, Instant.now());
            s.applyGroup(NotificationToggleGroup.ACCOUNT, false, Instant.now());
            settingRepository.save(s);

            txTemplate.executeWithoutResult(t ->
                    publisher.publish(event(userId, NotificationType.ACCOUNT_SANCTION)));

            assertThat(inbox(userId)).hasSize(1);
        }

        @Test
        @DisplayName("차단 관계여도 적재된다 — 차단은 관계 생성 게이트지 알림 필터가 아니다(공통 #2)")
        void blockIsNotANotificationFilter() {
            UUID receiver = newUser();
            UUID actor = newUser();
            jdbc.update("INSERT INTO user_blocks (blocker_id, target_type, target_id, blocked_at) "
                    + "VALUES (?, 'USER', ?, NOW(3))", bytes(receiver), bytes(actor));

            txTemplate.executeWithoutResult(t ->
                    publisher.publish(event(receiver, NotificationType.WATCHER_REACTION)));

            assertThat(inbox(receiver))
                    .as("적재 경로에 차단 분기가 남아 있으면 여기서 0건이 된다").hasSize(1);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("적재 시점 스냅샷")
    class Snapshot {

        @Test
        @DisplayName("토글 그룹·탭을 레지스트리에서 복사해 둔다 — 나중에 귀속이 바뀌어도 과거 고지는 그대로다")
        void copiesGroupAndTab() {
            UUID userId = newUser();
            txTemplate.executeWithoutResult(t -> {
                publisher.publish(event(userId, NotificationType.ACCOUNT_SANCTION));
                publisher.publish(event(userId, NotificationType.VERIFICATION_RESULT));
            });

            assertThat(inbox(userId))
                    .extracting(Notification::toggleGroupEnum, Notification::tabEnum)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(
                                    NotificationToggleGroup.ACCOUNT, NotificationTab.NOTIFICATION),
                            org.assertj.core.groups.Tuple.tuple(
                                    NotificationToggleGroup.CHALLENGE, NotificationTab.NOTIFICATION));
        }

        @Test
        @DisplayName("제목·본문·딥링크는 적재 시점에 렌더링돼 고정된다 — 템플릿이 바뀌어도 과거 문구는 불변")
        void rendersAtStoreTime() {
            UUID userId = newUser();
            txTemplate.executeWithoutResult(t -> publisher.publish(
                    NotificationEvent.of(userId, NotificationType.APPEAL_RESULT,
                            Map.of(NotificationParams.APPEAL_ID, "ap-1"))));

            Notification n = inbox(userId).getFirst();
            assertThat(n.getTitle()).as("발행부가 아니라 레지스트리가 정한 문구다")
                    .isEqualTo("이의가 받아들여졌어요");
            assertThat(n.getBody()).isEqualTo("인증이 완료로 정정됐어요. 진행률과 연속 기록도 함께 되돌렸어요.");
            assertThat(n.getDeeplink()).isEqualTo("ruleup://me/appeals");
        }

        @Test
        @DisplayName("발행부가 딥링크를 재정의하면 그것을 쓴다 — 챌린지 제목 거부는 진입점이 다르다")
        void deeplinkOverride() {
            UUID userId = newUser();
            txTemplate.executeWithoutResult(t -> publisher.publish(
                    NotificationEvent.of(userId, NotificationType.MODERATION_REJECTED,
                                    Map.of(NotificationParams.VARIANT, "CHALLENGE_TEXT",
                                            NotificationParams.CHALLENGE_TITLE, "아침 러닝",
                                            NotificationParams.TARGET_KEY, "challenge_title",
                                            NotificationParams.EVENT_KEY, "m1"))
                            .withDeeplink("ruleup://challenges/c-9/edit")));

            assertThat(inbox(userId).getFirst().getDeeplink())
                    .isEqualTo("ruleup://challenges/c-9/edit");
        }

        @Test
        @DisplayName("감시자 통지의 challenge_id 는 NULL 이다 — 수신자의 「내 챌린지」에 그 방이 없다")
        void watcherNoticeHasNoChallengeCounter() {
            UUID watcher = newUser();
            txTemplate.executeWithoutResult(t -> publisher.publish(
                    NotificationEvent.of(watcher, NotificationType.PENALTY_FAILURE_SHARED,
                            Map.of(NotificationParams.ACTOR_NAME, "루피",
                                    NotificationParams.CHALLENGE_TITLE, "아침 러닝",
                                    NotificationParams.ROUTINE_NAME, "5km 달리기",
                                    NotificationParams.EVENT_KEY, "p1",
                                    NotificationParams.NOTICE_ID, "n-1",
                                    NotificationParams.CHALLENGE_ID, "c-1",
                                    NotificationParams.ROUTINE_ID, "r-1",
                                    NotificationParams.TARGET_USER_ID, "u-1"))));

            assertThat(inbox(watcher).getFirst().getChallengeId())
                    .as("카운터 귀속 전용 컬럼이라 방 멤버가 아닌 수신자에게는 자리가 없다").isNull();
        }

        @Test
        @DisplayName("억제를 쓰지 않는 타입은 suppress_key 가 null 이다 — 인덱스에 빈 엔트리를 늘리지 않는다")
        void suppressKeyOnlyForSixTypes() {
            UUID userId = newUser();
            txTemplate.executeWithoutResult(t -> {
                publisher.publish(event(userId, NotificationType.ACCOUNT_SANCTION));
                publisher.publish(NotificationEvent.of(userId, NotificationType.TIER_BOUNDARY_NEAR,
                        Map.of(NotificationParams.EVENT_KEY, "t1",
                                NotificationParams.DIRECTION, "UP")));
            });

            assertThat(inbox(userId).stream().filter(n -> n.getSuppressKey() != null))
                    .singleElement()
                    .extracting(Notification::getType).isEqualTo("TIER_BOUNDARY_NEAR");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("dedup_key — 발행 멱등")
    class Idempotency {

        @Test
        @DisplayName("같은 멱등키로 두 번 발행해도 한 행만 남는다 — UNIQUE 가 INSERT 단계에서 막는다")
        void sameKeyStoresOnce() {
            UUID userId = newUser();
            Map<String, String> params = Map.of(NotificationParams.APPEAL_ID, "ap-same");

            txTemplate.executeWithoutResult(t -> publisher.publish(NotificationEvent.of(
                    userId, NotificationType.APPEAL_RESULT, params)));
            txTemplate.executeWithoutResult(t -> publisher.publish(NotificationEvent.of(
                    userId, NotificationType.APPEAL_RESULT, params)));

            assertThat(inbox(userId)).hasSize(1);
        }

        @Test
        @DisplayName("두 번째 발행은 큐에도 들어가지 않는다 — 같은 푸시가 두 번 울리면 안 된다")
        void duplicateIsNotEnqueued() {
            UUID userId = newUser();
            Map<String, String> params = Map.of(NotificationParams.APPEAL_ID, "ap-dup");

            txTemplate.executeWithoutResult(t -> publisher.publish(NotificationEvent.of(
                    userId, NotificationType.APPEAL_RESULT, params)));
            txTemplate.executeWithoutResult(t -> publisher.publish(NotificationEvent.of(
                    userId, NotificationType.APPEAL_RESULT, params)));

            assertThat(spy().sent).hasSize(1);
        }

        @Test
        @DisplayName("유저가 다르면 같은 대상이어도 각각 적재된다")
        void keyIsScopedToUser() {
            UUID a = newUser();
            UUID b = newUser();
            Map<String, String> params = Map.of(NotificationParams.ANNOUNCEMENT_ID, "an-1");

            txTemplate.executeWithoutResult(t -> {
                publisher.publish(NotificationEvent.authored(a, NotificationType.ANNOUNCEMENT,
                        "공지", "본문", params));
                publisher.publish(NotificationEvent.authored(b, NotificationType.ANNOUNCEMENT,
                        "공지", "본문", params));
            });

            assertThat(inbox(a)).hasSize(1);
            assertThat(inbox(b)).hasSize(1);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("큐 투입 대상")
    class Enqueue {

        @Test
        @DisplayName("공지는 적재만 되고 큐에 들어가지 않는다 — pushable=false")
        void announcementIsNeverQueued() {
            UUID userId = newUser();
            txTemplate.executeWithoutResult(t -> publisher.publish(NotificationEvent.authored(
                    userId, NotificationType.ANNOUNCEMENT, "점검 안내", "본문",
                    Map.of(NotificationParams.ANNOUNCEMENT_ID, "an-2"))));

            assertThat(inbox(userId)).hasSize(1);
            assertThat(spy().sent).isEmpty();
        }

        @Test
        @DisplayName("큐 메시지에 렌더링 결과가 다 들어간다 — 컨슈머가 notifications 를 다시 읽지 않는다")
        void messageCarriesRenderedPayload() {
            UUID userId = newUser();
            txTemplate.executeWithoutResult(t -> publisher.publish(NotificationEvent.of(
                    userId, NotificationType.APPEAL_RESULT,
                    Map.of(NotificationParams.APPEAL_ID, "ap-payload"))));

            NotificationMessage m = spy().sent.getFirst();
            assertThat(m.userId()).isEqualTo(userId);
            assertThat(m.type()).isEqualTo("APPEAL_RESULT");
            assertThat(m.toggleGroup()).isEqualTo(NotificationToggleGroup.ACCOUNT);
            assertThat(m.tab()).isEqualTo(NotificationTab.NOTIFICATION);
            assertThat(m.title()).isEqualTo("이의가 받아들여졌어요");
            assertThat(m.body()).isEqualTo("인증이 완료로 정정됐어요. 진행률과 연속 기록도 함께 되돌렸어요.");
            assertThat(m.deeplink()).isEqualTo("ruleup://me/appeals");
            assertThat(m.suppressKey()).isNull();
        }

        @Test
        @DisplayName("enqueue 가 실패해도 적재는 남는다 — 공통 3절이 푸시 유실을 허용한다")
        void enqueueFailureDoesNotUndoTheStore() {
            UUID userId = newUser();
            spy().failOnce = true;

            txTemplate.executeWithoutResult(t ->
                    publisher.publish(event(userId, NotificationType.ACCOUNT_SANCTION)));

            assertThat(inbox(userId)).as("고지는 이미 성립했다").hasSize(1);
        }
    }

    private static byte[] bytes(UUID u) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
