package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 멱등키의 <b>동시</b> 발행 — ECS 멀티 태스크에서 실제로 일어나는 경합이다.
 *
 * <p>백엔드 4-4 는 리마인더 크론에 ShedLock 을 두지 않고 {@code dedup_key} 의 UNIQUE 하나로
 * 멀티 태스크 중복 실행을 막는다고 적었다. 즉 <b>같은 키가 동시에 두 번 들어오는 것은 설계가
 * 전제한 정상 경로</b>이고, 그 충돌이 예외로 올라오면 적재가 도메인 트랜잭션 안에 있으므로
 * <b>인증 판정·강퇴 처리가 같이 롤백된다</b>(백엔드 4-1 이 금지하는 바로 그 사고다).
 *
 * <p>기존 멱등 테스트는 트랜잭션을 <b>순차로</b> 돌려서, 선조회가 통과한 뒤 남의 커밋이 끼어드는
 * 이 구간을 볼 수 없었다. 여기서는 한쪽이 커밋하지 않은 채 다른 쪽이 같은 키를 INSERT 하도록
 * 래치로 겹쳐 둔다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class NotificationConcurrentPublishIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired NotificationPublisher publisher;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("겹친 트랜잭션 둘이 같은 키를 넣어도 한 행만 남고, 어느 쪽도 롤백되지 않는다")
    void concurrentDuplicateDoesNotRollBackTheDomain() throws Exception {
        UUID userId = newUser();
        String appealId = "ap-con" + SEQ.incrementAndGet();
        String sharedKey = NotificationType.APPEAL_RESULT.dedupKey(userId,
                Map.of(NotificationParams.APPEAL_ID, appealId));

        // 먼저 INSERT 한 쪽이 커밋을 미루고, 그동안 뒤쪽이 같은 키를 밀어 넣는다.
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();

        Thread first = new Thread(() -> txTemplate.executeWithoutResult(t -> {
            publisher.publish(appealResult(userId, appealId));
            // 같은 트랜잭션의 도메인 작업 자리 — 이것이 살아남아야 판정이 보존된 것이다.
            publisher.publish(marker(userId, "first"));
            inserted.countDown();
            await(secondStarted);
            pause();   // 뒤쪽의 INSERT 가 실제로 잠금에 걸릴 시간을 준다
        }));
        Thread second = new Thread(() -> {
            await(inserted);
            txTemplate.executeWithoutResult(t -> {
                secondStarted.countDown();
                publisher.publish(appealResult(userId, appealId));   // 앞쪽이 커밋할 때까지 막힌다
                publisher.publish(marker(userId, "second"));
            });
        });
        first.setUncaughtExceptionHandler((th, e) -> firstError.set(e));
        second.setUncaughtExceptionHandler((th, e) -> secondError.set(e));

        first.start();
        second.start();
        first.join(30_000);
        second.join(30_000);

        assertThat(firstError.get()).as("먼저 넣은 트랜잭션").isNull();
        assertThat(secondError.get())
                .as("경합에서 밀린 트랜잭션 — 중복 키가 예외로 올라오면 도메인까지 롤백된다")
                .isNull();

        assertThat(countByDedupKey(sharedKey)).as("같은 멱등키는 한 행뿐이다").isEqualTo(1);
        assertThat(countByDedupKey(markerKey(userId, "first")))
                .as("앞쪽 트랜잭션의 다른 적재").isEqualTo(1);
        assertThat(countByDedupKey(markerKey(userId, "second")))
                .as("밀린 쪽의 도메인 작업이 살아 있다 — 롤백되지 않았다").isEqualTo(1);
    }

    // ===== 도우미 =====

    private NotificationEvent appealResult(UUID userId, String appealId) {
        return NotificationEvent.of(userId, NotificationType.APPEAL_RESULT,
                Map.of(NotificationParams.APPEAL_ID, appealId));
    }

    /** 같은 트랜잭션에 딸린 별개의 적재. 롤백됐는지 확인하는 표식이다. */
    private NotificationEvent marker(UUID userId, String tag) {
        return NotificationEvent.of(userId, NotificationType.TERMS_UPDATED,
                Map.of(NotificationParams.EVENT_KEY, tag));
    }

    private String markerKey(UUID userId, String tag) {
        return NotificationType.TERMS_UPDATED.dedupKey(userId,
                Map.of(NotificationParams.EVENT_KEY, tag));
    }

    private int countByDedupKey(String dedupKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE dedup_key = ?", Integer.class, dedupKey);
        return count == null ? 0 : count;
    }

    private UUID newUser() {
        String tag = "con" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com", "c" + Long.toString(System.nanoTime(), 36), null,
                List.of())).getId();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("래치 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
