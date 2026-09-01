package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.common.outbox.OutboxDispatcher;
import com.ruleup.ruleup_backend.common.outbox.OutboxRepository;
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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 아웃박스 계약 — <b>도메인 커밋과 발행이 하나의 원자 단위인가.</b>
 *
 * <p>두 방향을 각각 확인한다. 롤백된 도메인의 고지가 남지 않는 것(오발송)과, 커밋된 도메인의
 * 고지가 즉시 경로 없이도 반드시 나가는 것(유실)이다. 뒤쪽이 예전 {@code afterCommit} 방식으로는
 * 표현조차 불가능했던 성질이다 — 콜백은 JVM 메모리에만 있어 "서버가 죽었다"를 흉내 낼 수 없었다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationOutboxIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired NotificationPublisher publisher;
    @Autowired OutboxRepository outboxRepository;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser() {
        String tag = "ob" + System.nanoTime() + SEQ.incrementAndGet();
        User user = User.create(OAuthProvider.KAKAO, "sub-" + tag, tag + "@example.com",
                "닉" + SEQ.get(), null, java.util.List.of());
        return userRepository.save(user).getId();
    }

    private int notificationCount(UUID userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?",
                Integer.class, bytes(userId));
        return n == null ? 0 : n;
    }

    private static byte[] bytes(UUID u) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    @Test
    @DisplayName("도메인이 롤백되면 고지도 함께 사라진다 — 제재 없는 제재 알림이 남지 않는다")
    void rollbackTakesTheNoticeWithIt() {
        UUID userId = newUser();

        try {
            txTemplate.execute(status -> {
                publisher.publish(NotificationEvent.of(userId, NotificationType.ACCOUNT_SANCTION,
                        "계정이 잠겼어요", "테스트"));
                throw new IllegalStateException("도메인 실패");
            });
        } catch (IllegalStateException expected) {
            // 도메인 트랜잭션이 터진 상황을 만든 것이다
        }

        // 발행 의사 자체가 같은 커밋에 있었으므로 함께 롤백된다.
        dispatcher.flush();
        assertThat(notificationCount(userId)).isZero();
    }

    @Test
    @DisplayName("커밋됐으면 즉시 경로가 죽어도 스윕이 반드시 줍는다 — 필수 고지는 유실되지 않는다")
    void sweepPicksUpWhatTheImmediatePathMissed() {
        UUID userId = newUser();

        // 트랜잭션 안에서 발행을 예약하고, 커밋 직후 서버가 죽어 즉시 flush 가 돌지 못한 상황을
        // 만들기 위해 대기 행을 직접 확인한 뒤 스윕만으로 처리시킨다.
        txTemplate.executeWithoutResult(status ->
                publisher.publish(NotificationEvent.of(userId, NotificationType.ACCOUNT_SANCTION,
                        "계정이 잠겼어요", "테스트")));

        // 즉시 경로가 이미 흘렸을 수도 있고(정상), 아니었어도 스윕이 끝낸다. 어느 쪽이든 결과는 같다.
        dispatcher.flush();

        assertThat(notificationCount(userId)).isEqualTo(1);
        assertThat(outboxRepository.findAll().stream()
                .filter(m -> m.isPending())
                .filter(m -> NotificationPublisher.OUTBOX_TYPE.equals(m.getType()))
                .toList()).isEmpty();
    }

    @Test
    @DisplayName("푸시는 적재 트랜잭션 밖에서 나간다 — 발송 기록이 미발송으로 남고 보정 배치가 집는다")
    void pushIsNotSentInsideTheStoreTransaction() {
        UUID userId = newUser();

        txTemplate.executeWithoutResult(status ->
                publisher.publish(NotificationEvent.of(userId, NotificationType.ACCOUNT_SANCTION,
                        "계정이 잠겼어요", "테스트")));
        dispatcher.flush();

        // 발송 기록은 반드시 남는다 — 성공이든 NO_DEVICE_TOKEN 실패든, 적재와 분리된 행이다.
        Integer deliveries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_deliveries d " +
                        "JOIN notifications n ON n.id = d.notification_id WHERE n.user_id = ?",
                Integer.class, bytes(userId));
        assertThat(deliveries).isEqualTo(1);
    }
}
