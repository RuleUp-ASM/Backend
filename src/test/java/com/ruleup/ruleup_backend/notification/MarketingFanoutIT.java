package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.agreement.UserAgreementStateRepository;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreementState;
import com.ruleup.ruleup_backend.notification.announcement.Announcement;
import com.ruleup.ruleup_backend.notification.announcement.AnnouncementFanoutJob;
import com.ruleup.ruleup_backend.notification.announcement.AnnouncementRepository;
import com.ruleup.ruleup_backend.notification.domain.Notification;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 광고 팬아웃 — <b>동의자에게만, 캠페인 단위로</b>.
 *
 * <p>발송 판정에도 동의 게이트가 있지만 그것은 푸시만 막는다. 여기서 거르지 않으면 미동의자의
 * <b>알림함에 광고가 적재</b>되고, 그건 푸시를 막는 것으로 되돌려지지 않는다.
 *
 * <p>캠페인 레지스트리를 새로 만들지 않고 공지 파이프라인을 재사용한다 — 공지 id 하나가
 * 멱등 키이자 {@code campaign_id} 라 24시간 억제까지 그대로 붙는다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class MarketingFanoutIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired AnnouncementFanoutJob fanoutJob;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserAgreementStateRepository stateRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate txTemplate;

    private UUID newUser() {
        String tag = "mk" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com",
                "k" + Long.toString(System.nanoTime(), 36), null, List.of())).getId();
    }

    private void marketingConsent(UUID userId, boolean agreed) {
        txTemplate.executeWithoutResult(t -> stateRepository.save(UserAgreementState.of(
                userId, AgreementType.MARKETING, agreed, "1.0", Instant.now())));
    }

    private UUID publish(Announcement.Kind kind) {
        return txTemplate.execute(t -> announcementRepository.save(Announcement.of(
                kind, "캠페인 " + SEQ.incrementAndGet(), "본문", null,
                UUID.randomUUID(), null, Instant.now())).getId());
    }

    private List<Notification> noticesOf(UUID userId, String type) {
        return notificationRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(n -> type.equals(n.getType()))
                .toList();
    }

    // =====================================================================
    @Nested
    @DisplayName("수신자")
    class Audience {

        @Test
        @DisplayName("동의자에게만 적재된다 — 미동의자는 알림함에도 광고가 남지 않는다")
        void onlyConsentedReceive() {
            UUID consented = newUser();
            UUID declined = newUser();
            UUID never = newUser();          // 약관 행 자체가 없다
            marketingConsent(consented, true);
            marketingConsent(declined, false);

            publish(Announcement.Kind.MARKETING);
            fanoutJob.fanOutPending();

            assertThat(noticesOf(consented, "MARKETING")).hasSize(1);
            assertThat(noticesOf(declined, "MARKETING")).as("철회한 사람").isEmpty();
            assertThat(noticesOf(never, "MARKETING")).as("동의한 적 없는 사람").isEmpty();
        }

        @Test
        @DisplayName("운영 공지는 동의와 무관하게 전원에게 간다")
        void operationalNoticeIgnoresConsent() {
            UUID never = newUser();

            publish(Announcement.Kind.MAINTENANCE);
            fanoutJob.fanOutPending();

            assertThat(noticesOf(never, "ANNOUNCEMENT")).hasSize(1);
            assertThat(noticesOf(never, "MARKETING")).isEmpty();
        }
    }

    @Nested
    @DisplayName("캠페인 키")
    class CampaignKey {

        @Test
        @DisplayName("알림 탭에 쌓인다 — 공지 탭이 아니다")
        void landsOnNotificationTab() {
            UUID userId = newUser();
            marketingConsent(userId, true);

            publish(Announcement.Kind.MARKETING);
            fanoutJob.fanOutPending();

            assertThat(noticesOf(userId, "MARKETING")).singleElement()
                    .satisfies(n -> assertThat(n.tabEnum().code())
                            .as("광고는 공지 탭이 아니라 알림 탭이다").isZero());
        }

        @Test
        @DisplayName("같은 캠페인을 다시 펴도 한 번만 적재된다")
        void idempotentPerCampaign() {
            UUID userId = newUser();
            marketingConsent(userId, true);
            UUID campaignId = publish(Announcement.Kind.MARKETING);

            fanoutJob.fanOutPending();
            // 완료 표시를 지우고 다시 펴도(재개 경로) 중복되지 않아야 한다.
            txTemplate.executeWithoutResult(t -> announcementRepository.findById(campaignId)
                    .ifPresent(a -> a.markFannedOut(0, null)));
            fanoutJob.fanOutPending();

            assertThat(noticesOf(userId, "MARKETING"))
                    .as("공지 id 가 곧 멱등 키다").hasSize(1);
        }
    }
}
