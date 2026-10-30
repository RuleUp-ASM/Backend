package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.repository.NotificationRepository;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.agreement.UserAgreementStateRepository;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreementState;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.terms.TermsUpdateNoticeBatch;
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
 * 약관 개정 고지 — <b>대상 집합이 재동의 판정과 같아야</b> 한다.
 *
 * <p>기준이 어긋나면 「알림은 왔는데 동의 화면에는 재동의 항목이 없다」(또는 그 반대)가 되고,
 * 그 어긋남은 사용자가 문의를 넣어야 드러난다. 그래서 여기서 못 박는 것은 문구가 아니라
 * <b>누가 받고 누가 안 받는가</b>다 — 필수 약관만, 이미 동의해 둔 사람만, 개정당 한 번만.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class TermsUpdateNoticeIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired TermsUpdateNoticeBatch batch;
    @Autowired UserAgreementStateRepository stateRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired AppProperties props;
    @Autowired NotificationPublisher publisher;

    private UUID newUser() {
        String tag = "tu" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com",
                "t" + Long.toString(System.nanoTime(), 36), null, List.of())).getId();
    }

    private String currentVersion(AgreementType type) {
        return props.client().termsVersions().of(type);
    }

    /** 설정된 현행 버전과 반드시 다른 값 — 테스트가 설정값에 묶이지 않게 한다. */
    private String olderThanCurrent(AgreementType type) {
        return currentVersion(type) + "-old";
    }

    private void agreeAt(UUID userId, AgreementType type, String version) {
        txTemplate.executeWithoutResult(t -> stateRepository.save(
                UserAgreementState.of(userId, type, true, version, Instant.now())));
    }

    private List<Notification> noticesOf(UUID userId) {
        return notificationRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(n -> "TERMS_UPDATED".equals(n.getType()))
                .toList();
    }

    // =====================================================================
    @Nested
    @DisplayName("대상")
    class Targets {

        @Test
        @DisplayName("필수 약관을 구버전으로 동의해 둔 사람이 받는다")
        void outdatedRequiredAgreementReceives() {
            UUID userId = newUser();
            agreeAt(userId, AgreementType.TOS, olderThanCurrent(AgreementType.TOS));

            batch.notifyOutdated();

            assertThat(noticesOf(userId)).singleElement()
                    .satisfies(n -> assertThat(n.getDeeplink())
                            .isEqualTo("ruleup://settings/agreements"));
        }

        @Test
        @DisplayName("현행 버전에 동의한 사람은 받지 않는다")
        void currentVersionIsSilent() {
            UUID userId = newUser();
            agreeAt(userId, AgreementType.TOS, currentVersion(AgreementType.TOS));

            batch.notifyOutdated();

            assertThat(noticesOf(userId)).isEmpty();
        }

        @Test
        @DisplayName("선택 약관은 개정돼도 대상이 아니다 — 화면을 막지 않으므로 재동의도 없다")
        void optionalAgreementIsNotTargeted() {
            UUID userId = newUser();
            agreeAt(userId, AgreementType.MARKETING, olderThanCurrent(AgreementType.MARKETING));

            batch.notifyOutdated();

            assertThat(noticesOf(userId)).isEmpty();
        }
    }

    @Nested
    @DisplayName("반복 실행")
    class Idempotency {

        @Test
        @DisplayName("같은 개정에 대해서는 매일 돌아도 한 번만 적재된다")
        void publishesOncePerRevision() {
            UUID userId = newUser();
            agreeAt(userId, AgreementType.PRIVACY, olderThanCurrent(AgreementType.PRIVACY));

            batch.notifyOutdated();
            batch.notifyOutdated();
            batch.notifyOutdated();

            assertThat(noticesOf(userId))
                    .as("멱등키에 현행 버전이 들어 있다").hasSize(1);
        }

        @Test
        @DisplayName("서로 다른 필수 약관이 밀려 있으면 각각 받는다")
        void separateNoticePerAgreement() {
            UUID userId = newUser();
            agreeAt(userId, AgreementType.TOS, olderThanCurrent(AgreementType.TOS));
            agreeAt(userId, AgreementType.LOCATION, olderThanCurrent(AgreementType.LOCATION));

            batch.notifyOutdated();

            assertThat(noticesOf(userId)).hasSize(2);
        }
    }
    @Nested
    @DisplayName("대상이 한 쪽을 넘을 때")
    class Paging {

        /** 쪽 크기 2 — 대상 수가 쪽 크기를 넘고, 쪽을 넘기는 횟수도 쪽 크기를 넘게 만든다. */
        private TermsUpdateNoticeBatch smallChunkBatch() {
            return new TermsUpdateNoticeBatch(stateRepository, publisher, props, 2);
        }

        @Test
        @DisplayName("앞쪽 사람이 재동의하지 않고 남아 있어도 뒤쪽 사람까지 모두 받는다 — 각자 한 번만")
        void reachesEveryoneBeyondFirstPage() {
            TermsUpdateNoticeBatch paged = smallChunkBatch();

            // 첫 실행에서 고지를 받고도 재동의하지 않은 사람들 — 다음 실행에서도 여전히 대상이다.
            List<UUID> notified = java.util.stream.Stream.generate(TermsUpdateNoticeIT.this::newUser)
                    .limit(3).toList();
            notified.forEach(u -> agreeAt(u, AgreementType.TOS, olderThanCurrent(AgreementType.TOS)));
            paged.notifyOutdated();

            // 그 뒤에 밀린 사람들. 쪽 크기(2)를 넘고, 앞의 대상까지 합치면 쪽을 넘기는 횟수도 2를 넘는다.
            List<UUID> later = java.util.stream.Stream.generate(TermsUpdateNoticeIT.this::newUser)
                    .limit(5).toList();
            later.forEach(u -> agreeAt(u, AgreementType.TOS, olderThanCurrent(AgreementType.TOS)));
            paged.notifyOutdated();
            paged.notifyOutdated();

            for (UUID userId : notified) {
                assertThat(noticesOf(userId)).as("먼저 받은 사람은 다시 받지 않는다 %s", userId).hasSize(1);
            }
            for (UUID userId : later) {
                assertThat(noticesOf(userId)).as("뒤쪽 사람도 받는다 %s", userId).hasSize(1);
            }
        }
    }
}
