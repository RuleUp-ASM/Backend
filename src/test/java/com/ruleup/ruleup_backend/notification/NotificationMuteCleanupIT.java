package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.notification.domain.NotificationMute;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 음소거 정리 — <b>방을 떠나면 그 방의 음소거도 사라진다</b>.
 *
 * <p>정리 경로가 없어서 탈퇴·강퇴한 방의 음소거가 설정 목록에 영영 남았고, 같은 방에 다시
 * 들어가면 <b>과거의 음소거가 되살아나</b> 사용자가 켠 적 없는 상태로 알림이 막혔다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class NotificationMuteCleanupIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired NotificationMuteCleaner cleaner;
    @Autowired NotificationMuteRepository muteRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser() {
        String tag = "mc" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com",
                "m" + Long.toString(System.nanoTime(), 36), null, List.of())).getId();
    }

    /** 음소거 행은 챌린지에 FK 가 걸려 있어 실재하는 방이 필요하다. */
    private UUID insertChallenge(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO challenges " +
                        "(id, owner_id, title, ai_title, description, category, mode, capacity, " +
                        " repeat_days, duration_days, start_date, end_date, verification_config, " +
                        " params, penalty_config, reward_config, anonymity, status, " +
                        " moderation_status, ai_assisted, participant_count) " +
                        "VALUES (?, ?, '정리방', '정리방', '설명', 'HEALTH', 'GROUP', 50, " +
                        " '[\"MON\"]', 14, DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), " +
                        " DATE_ADD(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 14 DAY), " +
                        " '{\"selectedMethod\":\"MANUAL\",\"verificationType\":\"MANUAL\"," +
                        "\"signalSource\":\"SELF_CHECK\",\"wearableReq\":\"NONE\"," +
                        "\"requiredPermissions\":[]}', '{}', '{\"mannerDeduction\":1.0}', " +
                        " '{\"mannerGain\":1.0}', 'REAL', 'ACTIVE', 'NONE', 1, 1)",
                bytes(id), bytes(ownerId));
        return id;
    }

    private void mute(UUID userId, UUID challengeId) {
        txTemplate.executeWithoutResult(t ->
                muteRepository.save(NotificationMute.of(userId, challengeId, Instant.now())));
    }

    private boolean muted(UUID userId, UUID challengeId) {
        return muteRepository.existsById(new NotificationMute.Key(userId, challengeId));
    }

    private static byte[] bytes(UUID u) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    // =====================================================================
    @Nested
    @DisplayName("한 사람이 한 방을 떠났을 때")
    class LeavingOneRoom {

        @Test
        @DisplayName("그 방의 음소거만 지운다 — 다른 방은 그대로다")
        void clearsOnlyThatRoom() {
            UUID userId = newUser();
            UUID left = insertChallenge(userId);
            UUID stayed = insertChallenge(userId);
            mute(userId, left);
            mute(userId, stayed);

            cleaner.clearMute(userId, left);

            assertThat(muted(userId, left)).isFalse();
            assertThat(muted(userId, stayed)).as("남아 있는 방은 건드리지 않는다").isTrue();
        }

        @Test
        @DisplayName("음소거한 적 없어도 예외가 아니다 — 탈퇴가 이것 때문에 실패하면 안 된다")
        void isIdempotent() {
            UUID userId = newUser();
            UUID challengeId = insertChallenge(userId);

            cleaner.clearMute(userId, challengeId);

            assertThat(muted(userId, challengeId)).isFalse();
        }

        @Test
        @DisplayName("남의 음소거는 지우지 않는다")
        void doesNotTouchOtherUsers() {
            UUID mine = newUser();
            UUID other = newUser();
            UUID room = insertChallenge(mine);
            mute(mine, room);
            mute(other, room);

            cleaner.clearMute(mine, room);

            assertThat(muted(other, room)).isTrue();
        }
    }

    @Nested
    @DisplayName("방이 끝났을 때")
    class RoomCompleted {

        @Test
        @DisplayName("그 방의 음소거를 전원 분 지운다")
        void clearsEveryoneInRoom() {
            UUID owner = newUser();
            UUID member = newUser();
            UUID room = insertChallenge(owner);
            UUID otherRoom = insertChallenge(owner);
            mute(owner, room);
            mute(member, room);
            mute(owner, otherRoom);

            cleaner.clearMutesOfChallenge(room);

            assertThat(muted(owner, room)).isFalse();
            assertThat(muted(member, room)).isFalse();
            assertThat(muted(owner, otherRoom)).as("다른 방은 그대로다").isTrue();
        }
    }

    @Nested
    @DisplayName("회원이 탈퇴했을 때")
    class UserWithdrawn {

        @Test
        @DisplayName("그 사람의 음소거를 방 가리지 않고 전부 지운다")
        void clearsAllRoomsOfUser() {
            UUID userId = newUser();
            UUID other = newUser();
            UUID first = insertChallenge(userId);
            UUID second = insertChallenge(userId);
            mute(userId, first);
            mute(userId, second);
            mute(other, first);

            cleaner.clearMutesOfUser(userId);

            assertThat(muted(userId, first)).isFalse();
            assertThat(muted(userId, second)).isFalse();
            assertThat(muted(other, first)).as("남의 것은 그대로다").isTrue();
        }
    }
}
