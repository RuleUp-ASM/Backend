package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.score.domain.CycleResult;
import com.ruleup.ruleup_backend.score.domain.StreakWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연속 실패 경고 시점 — <b>정확히 한 번</b> 울려야 한다.
 *
 * <p>값(2사이클 경고 · 3사이클 강퇴)은 {@code ChallengeStreak} 이 원본으로 적어 둔 것이다.
 * 한 칸만 밀려도 예외가 아니라 매 사이클 반복되는 잘못된 알림이 되고, 그 사실은 사용자가
 * 문의를 넣어야 드러난다. 그래서 경계를 테스트로 못 박는다.
 */
class StreakWarningTest {

    @Test
    @DisplayName("2사이클 연속 실패에서 정확히 한 번 울린다")
    void warnsExactlyAtSecondCycle() {
        assertThat(StreakWarning.shouldWarn(CycleResult.FAILURE, 2)).isTrue();
    }

    @Test
    @DisplayName("1사이클에는 울리지 않는다 — 한 주 미달마다 경고하면 의미가 닳는다")
    void silentOnFirstFailure() {
        assertThat(StreakWarning.shouldWarn(CycleResult.FAILURE, 1)).isFalse();
    }

    @Test
    @DisplayName("3사이클 이후에는 울리지 않는다 — 이미 강퇴 집행 단계다")
    void silentAfterKickThreshold() {
        assertThat(StreakWarning.shouldWarn(CycleResult.FAILURE, 3)).isFalse();
        assertThat(StreakWarning.shouldWarn(CycleResult.FAILURE, 7)).isFalse();
    }

    @Test
    @DisplayName("성공·부분 달성은 대상이 아니다 — 연속 기록이 끊긴다")
    void silentUnlessFailure() {
        assertThat(StreakWarning.shouldWarn(CycleResult.SUCCESS, 2)).isFalse();
        assertThat(StreakWarning.shouldWarn(CycleResult.PARTIAL, 2)).isFalse();
    }
}
