package com.ruleup.ruleup_backend.push;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.push.domain.DevicePlatform;
import com.ruleup.ruleup_backend.push.domain.DeviceToken;
import com.ruleup.ruleup_backend.push.repository.DeviceTokenRepository;
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기기 토큰 위생 — <b>지우지 않고 내린다</b>가 규칙이고, 활성은 한 기기뿐이다.
 *
 * <p>세 가지가 서로 어긋나 있었다. 등록은 upsert 만 하고 이전 기기 토큰을 그대로 두었고(한 사람의
 * 알림이 두 기기에 뜬다), 무효 토큰 정리는 행을 <b>지웠으며</b>(V36 이 정한 「남겨서 CS 근거로
 * 쓴다」와 반대), 조회는 비활성 토큰까지 담아 왔다(이미 내린 기기로 계속 보낸다).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeviceTokenHygieneIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired DeviceTokenService deviceTokenService;
    @Autowired DeviceTokenRepository deviceTokenRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private static byte[] bytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    private UUID newUser() {
        String tag = "dt" + System.nanoTime() + SEQ.incrementAndGet();
        return userRepository.save(User.create(OAuthProvider.KAKAO, "sub-" + tag,
                tag + "@example.com",
                "d" + Long.toString(System.nanoTime(), 36), null, List.of())).getId();
    }

    private static String token(String tag) {
        return "tok-" + tag + "-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
    }

    private DeviceToken row(String token) {
        return deviceTokenRepository.findByToken(token).orElseThrow();
    }

    // =====================================================================
    @Nested
    @DisplayName("단일 활성 기기")
    class SingleActiveDevice {

        @Test
        @DisplayName("새 기기를 등록하면 이전 기기 토큰이 내려간다 — 두 기기에 동시에 뜨면 안 된다")
        void registeringNewDeviceDeactivatesOld() {
            UUID userId = newUser();
            String old = token("old");
            String fresh = token("new");

            deviceTokenService.register(userId, old, DevicePlatform.ANDROID);
            deviceTokenService.register(userId, fresh, DevicePlatform.IOS);

            assertThat(row(old).isActive()).as("이전 기기는 내려간다").isFalse();
            assertThat(row(fresh).isActive()).isTrue();
            assertThat(deviceTokenService.tokensOf(userId)).containsExactly(fresh);
        }

        @Test
        @DisplayName("같은 토큰을 다시 올리면 그대로 살아 있다 — 재등록은 교체가 아니다")
        void reRegisteringSameTokenKeepsItActive() {
            UUID userId = newUser();
            String only = token("same");

            deviceTokenService.register(userId, only, DevicePlatform.ANDROID);
            deviceTokenService.register(userId, only, DevicePlatform.ANDROID);

            assertThat(row(only).isActive()).isTrue();
            assertThat(deviceTokenService.tokensOf(userId)).containsExactly(only);
        }

        @Test
        @DisplayName("다른 유저의 토큰은 건드리지 않는다")
        void doesNotTouchOtherUsers() {
            UUID mine = newUser();
            UUID other = newUser();
            String otherToken = token("other");
            deviceTokenService.register(other, otherToken, DevicePlatform.ANDROID);

            deviceTokenService.register(mine, token("mine"), DevicePlatform.ANDROID);

            assertThat(row(otherToken).isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("지우지 않고 내린다")
    class DeactivateNotDelete {

        @Test
        @DisplayName("무효 토큰 정리는 행을 남긴다 — CS 가 언제 빠졌는지 볼 자리가 있어야 한다")
        void removeKeepsRow() {
            UUID userId = newUser();
            String dead = token("dead");
            deviceTokenService.register(userId, dead, DevicePlatform.ANDROID);

            deviceTokenService.remove(dead);

            assertThat(deviceTokenRepository.findByToken(dead))
                    .as("행이 남아 있다").isPresent();
            assertThat(row(dead).isActive()).isFalse();
            assertThat(deviceTokenService.tokensOf(userId)).isEmpty();
        }

        @Test
        @DisplayName("본인 해제도 행을 남기고 내린다")
        void unregisterKeepsRow() {
            UUID userId = newUser();
            String mine = token("mine");
            deviceTokenService.register(userId, mine, DevicePlatform.ANDROID);

            deviceTokenService.unregister(userId, mine);

            assertThat(row(mine).isActive()).isFalse();
            assertThat(deviceTokenService.tokensOf(userId)).isEmpty();
        }

        @Test
        @DisplayName("내려간 기기가 토큰을 다시 올리면 되살아난다")
        void reRegisterRevives() {
            UUID userId = newUser();
            String back = token("back");
            deviceTokenService.register(userId, back, DevicePlatform.ANDROID);
            deviceTokenService.remove(back);

            deviceTokenService.register(userId, back, DevicePlatform.ANDROID);

            assertThat(row(back).isActive()).isTrue();
            assertThat(deviceTokenService.tokensOf(userId)).containsExactly(back);
        }
    }

    @Nested
    @DisplayName("토큰 형식")
    class TokenFormat {

        @Test
        @DisplayName("공백·개행이 섞이면 400 INVALID_DEVICE_TOKEN — 발송 단계에서 조용히 실패하면 늦다")
        void rejectsWhitespace() {
            UUID userId = newUser();

            assertThatThrownBy(() -> deviceTokenService.register(userId, "tok with space",
                    DevicePlatform.ANDROID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_DEVICE_TOKEN);
        }

        @Test
        @DisplayName("컬럼 길이를 넘으면 거절한다 — 잘려 저장되면 다른 토큰이 된다")
        void rejectsTooLong() {
            UUID userId = newUser();

            assertThatThrownBy(() -> deviceTokenService.register(userId, "x".repeat(513),
                    DevicePlatform.ANDROID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_DEVICE_TOKEN);
        }

        @Test
        @DisplayName("빈 토큰도 거절한다")
        void rejectsBlank() {
            UUID userId = newUser();

            assertThatThrownBy(() -> deviceTokenService.register(userId, "   ",
                    DevicePlatform.ANDROID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // =====================================================================
    /**
     * 이미 쌓여 있던 중복 활성분 정리(V41).
     *
     * <p>단일 활성 기기 정책은 <b>등록 경로</b>에만 있다. V36 이 {@code isActive} 를 DEFAULT 1 로
     * 붙이면서 그 전에 쌓인 행이 전부 활성으로 켜졌으므로, 기기를 바꾼 적 있는 사용자는 마이그레이션
     * 없이는 옛 토큰이 활성으로 남는다 — 등록을 다시 하기 전까지 알림이 두 기기에 간다.
     *
     * <p>픽스처를 <b>서비스를 거치지 않고</b> 직접 심는 이유가 그것이다. 등록 경로로 만들면
     * {@code deactivateOthers} 가 이미 정리해 버려 마이그레이션이 할 일이 남지 않는다.
     *
     * <p>마이그레이션은 컨테이너가 뜰 때 이미 돌았다. 그래서 그 파일의 SQL 을 <b>그대로 읽어</b>
     * 실행한다 — 문장을 테스트에 복사해 두면 파일이 바뀔 때 테스트만 옛 문장을 검증하게 된다.
     */
    @Nested
    @DisplayName("쌓여 있던 중복 활성 정리(V41)")
    class DuplicateActiveBackfill {

        private void seedActive(UUID userId, String token, Instant at) {
            jdbc.update("INSERT INTO DeviceToken " +
                            "(id, userId, token, platform, isActive, lastSeenAt, createdAt) " +
                            "VALUES (?, ?, ?, 'ANDROID', 1, ?, ?)",
                    bytes(UUID.randomUUID()), bytes(userId), token,
                    Timestamp.from(at), Timestamp.from(at));
        }

        private void runMigration() throws Exception {
            String sql;
            try (var in = getClass()
                    .getResourceAsStream("/db/migration/V41__device_token_single_active.sql")) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            jdbc.execute(sql);
        }

        @Test
        @DisplayName("유저마다 가장 최근 토큰 하나만 남긴다 — V36 이 켜 둔 과거분을 정리한다")
        void keepsOnlyNewestPerUser() throws Exception {
            UUID userId = newUser();
            String oldest = token("bf-old");
            String middle = token("bf-mid");
            String newest = token("bf-new");
            Instant base = Instant.now().minusSeconds(3_600);
            seedActive(userId, oldest, base);
            seedActive(userId, middle, base.plusSeconds(600));
            seedActive(userId, newest, base.plusSeconds(1_200));

            runMigration();

            assertThat(row(newest).isActive()).as("가장 최근에 쓰인 기기는 살아남는다").isTrue();
            assertThat(row(middle).isActive()).isFalse();
            assertThat(row(oldest).isActive()).isFalse();
        }

        @Test
        @DisplayName("내린 행을 지우지는 않는다 — CS 가 언제 빠졌는지 볼 자리가 남아야 한다")
        void deactivatesWithoutDeleting() throws Exception {
            UUID userId = newUser();
            String stale = token("bf-keep-old");
            String live = token("bf-keep-new");
            Instant base = Instant.now().minusSeconds(3_600);
            seedActive(userId, stale, base);
            seedActive(userId, live, base.plusSeconds(600));

            runMigration();

            assertThat(deviceTokenRepository.findByToken(stale))
                    .as("행 자체는 남는다").isPresent();
            assertThat(row(stale).isActive()).isFalse();
        }

        @Test
        @DisplayName("활성이 하나뿐인 유저는 건드리지 않는다")
        void singleActiveIsUntouched() throws Exception {
            UUID userId = newUser();
            String only = token("bf-single");
            seedActive(userId, only, Instant.now().minusSeconds(60));

            runMigration();

            assertThat(row(only).isActive()).isTrue();
        }
    }
}
