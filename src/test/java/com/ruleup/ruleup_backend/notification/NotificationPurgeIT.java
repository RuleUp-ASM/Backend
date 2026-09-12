package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파기 배치 — <b>한 번 실행에서 밀린 분을 다 지우는가</b>가 계약이다.
 *
 * <p>구 구현은 청크 하나(5,000행)를 지우고 끝났다. 일 12~14만 건이 쌓이는 규모에서 그러면
 * 하루 5,000건씩만 빠져 <b>적체가 영구히 늘어난다</b> — 게다가 목록 조회에 보관 기간 조건이
 * 없었으므로, 적체된 6개월 지난 고지가 알림함에 그대로 다시 보였다.
 *
 * <p>청크 크기를 2로 덮어써서 검증한다. 5,000행을 실제로 넣으면 스위트가 감당할 수 없고,
 * 반복 소진이라는 성질은 청크 크기와 무관하게 같기 때문이다. 이 프로퍼티가 존재하는 이유가
 * 그것뿐이며 운영에서 만질 값이 아니다.
 */
@SpringBootTest(properties = "app.notification.purge.chunk=2")
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class NotificationPurgeIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired NotificationBatch batch;
    @Autowired NotificationPublisher publisher;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JdbcTemplate jdbc;

    /** {@code nickname} 은 VARCHAR(12) 이고 스위트가 DB 를 공유한다 — 짧고 겹치지 않아야 한다. */
    private UUID newUser() {
        String tag = "pg" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com",
                "p" + Long.toString(System.nanoTime(), 36), null, List.of())).getId();
    }

    private void store(UUID userId, String key) {
        txTemplate.executeWithoutResult(t -> publisher.publish(NotificationEvent.of(
                userId, NotificationType.APPEAL_RESULT, "제목-" + key, "본문-" + key,
                Map.of(NotificationParams.APPEAL_ID, key))));
    }

    /**
     * 보관 기간 밖으로 밀어낸다. SQL 안에서 상대 계산을 하는 이유는 {@code created_at} 이
     * DATETIME(3) 이라 자바에서 Instant 를 넣으면 JVM 시간대 해석이 끼어들기 때문이다.
     */
    private void backdate(UUID userId, int days) {
        jdbc.update("UPDATE notifications SET created_at = DATE_SUB(created_at, INTERVAL ? DAY)"
                + " WHERE user_id = ?", days, bytes(userId));
    }

    private int rowsOf(UUID userId) {
        return notificationRepository.findByUserIdOrderByIdDesc(userId).size();
    }

    private static byte[] bytes(UUID u) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    // =====================================================================
    @Nested
    @DisplayName("반복 소진")
    class Draining {

        @Test
        @DisplayName("청크를 넘겨도 남김없이 지운다 — 한 청크만 지우면 적체가 영구히 늘어난다")
        void drainsBeyondOneChunk() {
            UUID userId = newUser();
            for (int i = 0; i < 5; i++) store(userId, "old" + SEQ.incrementAndGet());
            backdate(userId, 200);   // 보관 180일을 넘긴다

            int deleted = batch.purgeExpired();

            assertThat(deleted).as("5건을 청크 2로 지우려면 3회 돌아야 한다").isGreaterThanOrEqualTo(5);
            assertThat(rowsOf(userId)).isZero();
        }

        /**
         * 반복 횟수가 청크 크기를 넘어가는 지점까지 밀어 넣는다.
         *
         * <p>앞 테스트(5건·청크 2)는 3회차에 끝나 <b>중단 조건이 무엇과 비교되는지</b>를 구분하지
         * 못한다. 반복 변수와 비교해도 우연히 같은 시점에 멈추기 때문이다. 10건이면 4회차에서
         * 「지운 2건 &lt; 회차 3」이 성립해 <b>남은 2건을 두고 돌아간다</b> — 그 잔여를 본다.
         */
        @Test
        @DisplayName("반복 횟수가 청크 크기를 넘어서도 끝까지 지운다 — 회차와 비교하면 잔여가 남는다")
        void drainsPastChunkSizedRoundCount() {
            UUID userId = newUser();
            for (int i = 0; i < 10; i++) store(userId, "old" + SEQ.incrementAndGet());
            backdate(userId, 200);

            int deleted = batch.purgeExpired();

            assertThat(rowsOf(userId)).as("한 건도 남기지 않는다").isZero();
            assertThat(deleted).as("10건을 전부 셌다").isGreaterThanOrEqualTo(10);
        }

        @Test
        @DisplayName("보관 기간 안의 알림은 건드리지 않는다")
        void keepsRowsInsideRetention() {
            UUID userId = newUser();
            store(userId, "fresh" + SEQ.incrementAndGet());

            batch.purgeExpired();

            assertThat(rowsOf(userId)).isEqualTo(1);
        }

        @Test
        @DisplayName("지울 것이 없으면 0을 준다 — 빈 실행이 예외로 가지 않는다")
        void emptyRunIsZero() {
            UUID userId = newUser();
            store(userId, "keep" + SEQ.incrementAndGet());

            batch.purgeExpired();
            int second = batch.purgeExpired();

            assertThat(second).isZero();
            assertThat(rowsOf(userId)).isEqualTo(1);
        }
    }
}
