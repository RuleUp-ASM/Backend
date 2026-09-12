package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.common.event.PermissionGapDetected;
import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실시간 권한 공백이 <b>알림함에도</b> 남는가.
 *
 * <p>구 구현은 이 경로에서 고스트(무음) 푸시만 적재했다. 무음 푸시는 앱을 깨우기만 하므로 앱을
 * 열지 않은 사용자에게는 아무 흔적도 남지 않고, 그 사이 자동 인증은 계속 skip 되다가 2사이클
 * 미해소로 강퇴된다 — 「아무 안내도 못 받았다」가 되는 경로다.
 *
 * <p>권한 재허용 고지를 발행하는 자리가 {@code PENDING_SETUP} 시간 배치에만 있었다는 것이
 * 핵심이다. <b>셋업을 마친 뒤</b> OS 에서 권한을 끈 사용자는 그 배치의 대상이 아니므로
 * 어느 쪽에도 걸리지 않았다. 절대 규칙 1(모든 알림은 예외 없이 알림 센터에 적재된다) 위반이다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class PermissionGapNotificationIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired ApplicationEventPublisher events;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;

    /** {@code nickname} 은 VARCHAR(12) 이고 스위트가 DB 를 공유한다 — 짧고 겹치지 않아야 한다. */
    private UUID newUser() {
        String tag = "pg" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com",
                "g" + Long.toString(System.nanoTime(), 36), null, List.of())).getId();
    }

    /**
     * 챌린지 행을 만들지 않는다 — 이 경로가 보는 것은 이벤트의 값뿐이고, 고지는
     * {@code challenge_id} 를 파라미터로만 쓴다(카운터 귀속이 아니라 딥링크 렌더링용).
     */
    private void detectGap(UUID userId, UUID challengeId, String signalType, LocalDate day) {
        events.publishEvent(new PermissionGapDetected(
                userId, challengeId, signalType, day, Instant.now()));
    }

    private List<Notification> noticesOf(UUID userId) {
        return notificationRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(n -> "PERMISSION_REGRANT_REQUIRED".equals(n.getType()))
                .toList();
    }

    @Test
    @DisplayName("실시간 권한 공백도 알림함에 쌓인다 — 무음 푸시만으로는 기록이 남지 않는다")
    void realtimeGapIsStored() {
        UUID userId = newUser();
        UUID challengeId = UUID.randomUUID();

        detectGap(userId, challengeId, "GEOFENCE", LocalDate.now(KST));

        assertThat(noticesOf(userId)).singleElement().satisfies(n -> {
            assertThat(n.getDeeplink())
                    .as("방별 인증 설정 화면으로 보낸다")
                    .isEqualTo("ruleup://challenges/" + challengeId + "/setup");
            assertThat(n.getTitle()).isNotBlank();
            assertThat(n.getBody()).isNotBlank();
        });
    }

    @Test
    @DisplayName("같은 날 같은 방·같은 신호는 한 번만 쌓인다 — sync 는 하루에도 여러 번 온다")
    void sameDayIsIdempotent() {
        UUID userId = newUser();
        UUID challengeId = UUID.randomUUID();
        LocalDate today = LocalDate.now(KST);

        detectGap(userId, challengeId, "GEOFENCE", today);
        detectGap(userId, challengeId, "GEOFENCE", today);
        detectGap(userId, challengeId, "GEOFENCE", today);

        assertThat(noticesOf(userId)).hasSize(1);
    }

    @Test
    @DisplayName("날짜가 바뀌면 다시 쌓인다 — 권한이 계속 막혀 있으면 계속 알려야 한다")
    void nextDayNotifiesAgain() {
        UUID userId = newUser();
        UUID challengeId = UUID.randomUUID();
        LocalDate today = LocalDate.now(KST);

        detectGap(userId, challengeId, "GEOFENCE", today.minusDays(1));
        detectGap(userId, challengeId, "GEOFENCE", today);

        assertThat(noticesOf(userId))
                .as("멱등 키가 고정값이면 둘째 날부터 영영 조용해진다").hasSize(2);
    }

    @Test
    @DisplayName("같은 날이어도 방이 다르면 따로 쌓인다 — 한쪽만 알리면 다른 방이 조용히 강퇴된다")
    void perChallenge() {
        UUID userId = newUser();
        LocalDate today = LocalDate.now(KST);

        detectGap(userId, UUID.randomUUID(), "GEOFENCE", today);
        detectGap(userId, UUID.randomUUID(), "GEOFENCE", today);

        assertThat(noticesOf(userId)).hasSize(2);
    }
}
