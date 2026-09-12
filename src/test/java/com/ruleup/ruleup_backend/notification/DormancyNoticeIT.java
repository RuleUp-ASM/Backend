package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.dormancy.DormancyNoticeBatch;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미접속 고지 — <b>상태를 바꾸지 않고 알림만</b> 낸다.
 *
 * <p>휴면은 상태가 아니라 {@code last_active_at} 으로 계산되는 값이고 로그인하면 저절로 풀린다.
 * 그래서 이 배치가 지켜야 하는 성질은 셋이다: 경계를 넘은 사람만 받을 것, 매일 돌아도 <b>한 번만</b>
 * 적재될 것, 더 무거운 탈퇴 예고가 휴면 예고에 가려지지 않을 것.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class DormancyNoticeIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired DormancyNoticeBatch batch;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser() {
        String tag = "dm" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com",
                "d" + Long.toString(System.nanoTime(), 36), null, List.of())).getId();
    }

    /** 마지막 활동 시각을 과거로 돌린다. SQL 안에서 상대 계산을 해 시간대 해석을 피한다. */
    private void silentFor(UUID userId, int days) {
        jdbc.update("UPDATE users SET last_active_at = DATE_SUB(NOW(3), INTERVAL ? DAY)"
                + " WHERE id = ?", days, bytes(userId));
    }

    private List<Notification> noticesOf(UUID userId, String type) {
        return notificationRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(n -> type.equals(n.getType()))
                .toList();
    }

    private static byte[] bytes(UUID u) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    // =====================================================================
    @Nested
    @DisplayName("경계")
    class Boundary {

        @Test
        @DisplayName("휴면 예고선을 넘으면 고지가 쌓인다")
        void notifiesPastDormancyLine() {
            UUID userId = newUser();
            silentFor(userId, 340);   // 기본 335일 초과

            batch.notifyInactive();

            assertThat(noticesOf(userId, "DORMANCY_NOTICE")).hasSize(1);
            assertThat(noticesOf(userId, "INACTIVE_WITHDRAWAL_NOTICE")).isEmpty();
        }

        @Test
        @DisplayName("아직 활동 중인 사람은 대상이 아니다")
        void skipsActiveUsers() {
            UUID userId = newUser();
            silentFor(userId, 10);

            batch.notifyInactive();

            assertThat(noticesOf(userId, "DORMANCY_NOTICE")).isEmpty();
        }
    }

    @Nested
    @DisplayName("더 무거운 쪽이 이긴다")
    class WithdrawalWins {

        @Test
        @DisplayName("탈퇴 예고선을 넘으면 휴면 예고 대신 탈퇴 예고만 간다")
        void onlyWithdrawalNotice() {
            UUID userId = newUser();
            silentFor(userId, 720);   // 기본 700일 초과

            batch.notifyInactive();

            assertThat(noticesOf(userId, "INACTIVE_WITHDRAWAL_NOTICE")).hasSize(1);
            assertThat(noticesOf(userId, "DORMANCY_NOTICE"))
                    .as("같은 날 두 통이 오면 더 무거운 쪽을 놓친다").isEmpty();
        }
    }

    @Nested
    @DisplayName("반복 실행")
    class Idempotency {

        @Test
        @DisplayName("매일 돌아도 같은 침묵 구간에서는 한 번만 적재된다")
        void publishesOncePerSilentSpell() {
            UUID userId = newUser();
            silentFor(userId, 340);

            batch.notifyInactive();
            batch.notifyInactive();
            batch.notifyInactive();

            assertThat(noticesOf(userId, "DORMANCY_NOTICE"))
                    .as("멱등키에 last_active_at 이 들어 있다").hasSize(1);
        }

        @Test
        @DisplayName("돌아왔다가 다시 잠잠해지면 새 고지가 나간다")
        void newSpellNotifiesAgain() {
            UUID userId = newUser();
            silentFor(userId, 340);
            batch.notifyInactive();

            // 다시 들어왔다가(활동 시각 갱신) 또 오래 비웠다 — 다른 침묵 구간이다.
            silentFor(userId, 350);
            batch.notifyInactive();

            assertThat(noticesOf(userId, "DORMANCY_NOTICE")).hasSize(2);
        }
    }
}
