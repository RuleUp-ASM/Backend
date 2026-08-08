package com.ruleup.ruleup_backend.user.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이전 닉네임 1주일 잠금의 판정 규칙 (회원 정책 §3).
 * 정책 문구가 "타인이 등록·변경에 사용 불가"라 <b>버린 본인</b>은 예외다 —
 * 심사 거부로 되돌아가는 경로(변경 주기 검사가 면제되는 구간)에서 실제로 필요하다.
 */
class NicknameReleaseLockTest {

    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    @DisplayName("잠금 기간은 7일이다")
    void locks_for_a_week() {
        NicknameReleaseLock lock = NicknameReleaseLock.of("이전닉", UUID.randomUUID(), NOW);

        assertThat(lock.getLockedUntil()).isEqualTo(NOW.plus(NicknamePolicy.RELEASE_LOCK));
        assertThat(NicknamePolicy.RELEASE_LOCK.toDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("타인은 막고, 버린 본인은 되돌릴 수 있다")
    void blocks_others_but_not_the_releaser() {
        UUID owner = UUID.randomUUID();
        NicknameReleaseLock lock = NicknameReleaseLock.of("이전닉", owner, NOW);

        assertThat(lock.blocks(UUID.randomUUID(), NOW)).as("타인").isTrue();
        assertThat(lock.blocks(owner, NOW)).as("버린 본인").isFalse();
        assertThat(lock.blocks(null, NOW)).as("주체 없음(신규 가입)").isTrue();
    }

    @Test
    @DisplayName("7일이 지나면 누구에게도 걸리지 않는다")
    void expires_after_the_window() {
        UUID owner = UUID.randomUUID();
        NicknameReleaseLock lock = NicknameReleaseLock.of("이전닉", owner, NOW);
        Instant expired = NOW.plus(NicknamePolicy.RELEASE_LOCK);

        assertThat(lock.isActiveAt(expired.minusSeconds(1))).isTrue();
        assertThat(lock.isActiveAt(expired)).as("경계 시각에는 이미 풀린다").isFalse();
        assertThat(lock.blocks(UUID.randomUUID(), expired)).isFalse();
    }

    @Test
    @DisplayName("같은 닉네임이 다시 버려지면 새 주인 기준으로 7일을 다시 센다")
    void relock_resets_owner_and_window() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        NicknameReleaseLock lock = NicknameReleaseLock.of("이전닉", first, NOW);

        Instant later = NOW.plusSeconds(3600);
        lock.relock(second, later);

        assertThat(lock.getLockedUntil()).isEqualTo(later.plus(NicknamePolicy.RELEASE_LOCK));
        assertThat(lock.blocks(second, later)).as("새로 버린 사람은 되돌릴 수 있다").isFalse();
        assertThat(lock.blocks(first, later)).as("이전 주인의 예외는 넘어오지 않는다").isTrue();
    }
}
