package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeActivationService;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeCompletionService;
import com.ruleup.ruleup_backend.challenge.service.RoomAdminService;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 라이프사이클·부정행위 고지가 <b>실제로 적재되는가</b>.
 *
 * <p>레지스트리에는 23종이 다 있는데 발행 지점이 없어 실제 사건이 나도 알림함이 비어 있던 타입들이다.
 * 그래서 여기서 보는 것은 문구가 아니라 <b>배선</b>이다 — 배치를 실제로 돌려서 그 사건으로
 * 행이 생기는지, 타입과 파라미터가 레지스트리와 맞물려 딥링크가 렌더되는지.
 *
 * <p>픽스처를 직접 심는다. 활성화 배치의 대상 조건이 「시작일 도달 + 모더레이션 통과 + UPCOMING」
 * 이라 <b>하나라도 어긋나면 0건을 조용히 통과</b>시키는 테스트가 되기 때문이다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class ChallengeLifecycleNotificationIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired ChallengeActivationService activationService;
    @Autowired ChallengeCompletionService completionService;
    @Autowired RoomAdminService roomAdminService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser() {
        String tag = "lc" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com",
                "l" + Long.toString(System.nanoTime(), 36), null, List.of())).getId();
    }

    /** 시작일은 오늘(KST), 모더레이션은 통과 상태로 심는다 — 상태만 바꿔 대상 여부를 가른다. */
    private UUID insertChallenge(UUID ownerId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO challenges " +
                        "(id, owner_id, title, ai_title, description, category, mode, capacity, " +
                        " repeat_days, duration_days, start_date, end_date, verification_config, " +
                        " params, penalty_config, reward_config, anonymity, status, " +
                        " moderation_status, ai_assisted, participant_count) " +
                        "VALUES (?, ?, '생명주기방', '생명주기방', '설명', 'HEALTH', 'GROUP', 50, " +
                        " '[\"MON\",\"TUE\",\"WED\",\"THU\",\"FRI\",\"SAT\",\"SUN\"]', 14, " +
                        " DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), " +
                        " DATE_ADD(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 14 DAY), " +
                        " '{\"selectedMethod\":\"MANUAL\",\"verificationType\":\"MANUAL\"," +
                        "\"signalSource\":\"SELF_CHECK\",\"wearableReq\":\"NONE\"," +
                        "\"requiredPermissions\":[]}', '{}', '{\"mannerDeduction\":1.0}', " +
                        " '{\"mannerGain\":1.0}', 'REAL', ?, 'NONE', 1, 1)",
                bytes(id), bytes(ownerId), status);
        return id;
    }

    private void insertActiveMembership(UUID challengeId, UUID userId, String role) {
        jdbc.update("INSERT INTO challenge_members (id, challenge_id, user_id, role, status) " +
                        "VALUES (?, ?, ?, ?, 'ACTIVE')",
                bytes(UUID.randomUUID()), bytes(challengeId), bytes(userId), role);
    }

    /** 종료 배치 대상이 되도록 종료일을 어제로 돌린다. */
    private void expire(UUID challengeId) {
        jdbc.update("UPDATE challenges SET end_date = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP()," +
                " '+00:00', '+09:00')), INTERVAL 1 DAY) WHERE id = ?", bytes(challengeId));
    }

    private void setCounter(UUID userId, int count) {
        jdbc.update("INSERT INTO user_challenge_counters (user_id, active_join_count) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE active_join_count = VALUES(active_join_count)",
                bytes(userId), count);
    }

    private List<Notification> notificationsOf(UUID userId, String type) {
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
    @DisplayName("챌린지 시작")
    class Started {

        @Test
        @DisplayName("시작일이 도달해 ACTIVE 로 전환되면 멤버 전원에게 고지가 쌓인다")
        void notifiesMembersOnActivation() {
            UUID owner = newUser();
            UUID member = newUser();
            UUID challengeId = insertChallenge(owner, "UPCOMING");
            insertActiveMembership(challengeId, owner, "OWNER");
            insertActiveMembership(challengeId, member, "MEMBER");

            activationService.activateDueChallenges();

            assertThat(notificationsOf(owner, "CHALLENGE_LIFECYCLE")).hasSize(1);
            assertThat(notificationsOf(member, "CHALLENGE_LIFECYCLE"))
                    .singleElement()
                    .satisfies(n -> {
                        assertThat(n.getChallengeId()).isEqualTo(challengeId);
                        assertThat(n.getDeeplink())
                                .as("challenge_id 가 파라미터로 들어가야 딥링크가 렌더된다")
                                .isEqualTo("ruleup://challenges/" + challengeId);
                    });
        }

        @Test
        @DisplayName("배치를 다시 돌려도 두 번 쌓이지 않는다 — dedup_key 가 막는다")
        void isIdempotentAcrossRuns() {
            UUID owner = newUser();
            UUID challengeId = insertChallenge(owner, "UPCOMING");
            insertActiveMembership(challengeId, owner, "OWNER");

            activationService.activateDueChallenges();
            activationService.activateDueChallenges();

            assertThat(notificationsOf(owner, "CHALLENGE_LIFECYCLE")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("챌린지 종료")
    class Ended {

        @Test
        @DisplayName("종료일이 지나 COMPLETED 로 마감되면 멤버 전원에게 고지가 쌓인다")
        void notifiesMembersOnCompletion() {
            UUID owner = newUser();
            setCounter(owner, 1);
            UUID challengeId = insertChallenge(owner, "ACTIVE");
            insertActiveMembership(challengeId, owner, "OWNER");
            expire(challengeId);

            completionService.completeEndedChallenges();

            assertThat(notificationsOf(owner, "CHALLENGE_LIFECYCLE"))
                    .singleElement()
                    .satisfies(n -> assertThat(n.getChallengeId()).isEqualTo(challengeId));
        }
    }

    @Nested
    @DisplayName("부정행위 강퇴")
    class CheatKick {

        @Test
        @DisplayName("일반 강퇴가 아니라 CHEAT_DETECTED 로 고지한다 — 진입점이 제재 이력이다")
        void publishesCheatType() {
            UUID owner = newUser();
            UUID cheater = newUser();
            UUID challengeId = insertChallenge(owner, "ACTIVE");
            insertActiveMembership(challengeId, owner, "OWNER");
            insertActiveMembership(challengeId, cheater, "MEMBER");

            roomAdminService.kickForCheat(challengeId, cheater);

            assertThat(notificationsOf(cheater, "CHALLENGE_KICKED"))
                    .as("일반 강퇴 타입으로는 나가지 않는다").isEmpty();
            assertThat(notificationsOf(cheater, "CHEAT_DETECTED"))
                    .singleElement()
                    .satisfies(n -> {
                        assertThat(n.getDeeplink()).isEqualTo("ruleup://me/sanctions");
                        assertThat(n.getChallengeId())
                                .as("영구 차단이라 「내 챌린지」에 그 방이 없다 — 카운터가 뜰 자리가 없다")
                                .isNull();
                    });
        }
    }
}
