package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.score.domain.CycleLimit;
import com.ruleup.ruleup_backend.score.domain.IntegerScore;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.score.domain.TierBands;
import com.ruleup.ruleup_backend.score.domain.TierPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 점수 산식 — 점수 및 티어 정책 §4.5 · §4.7, 티어·점수 백엔드 테크 스펙.
 *
 * <p>이 테스트가 지키는 것은 <b>불변식</b>이다. 정책이 표로 준 주간 총 배점(브론즈 +10 · 루비 −38 …)과
 * 일 단위로 쪼갠 반영 누계가 어긋나면 사용자가 받는 총점이 정책과 달라지므로, {@code f(N) = W} 를
 * 5티어 × 2축 × 주 1~7회 전 조합에 대해 고정한다 — 테크 스펙이 명시적으로 요구한 테스트다.
 *
 * <p>소수를 쓰지 않는 이유도 함께 고정한다. 언어 기본 {@code round()} 는 은행가 반올림일 수 있고
 * {@code double} 은 재현성이 없어, 산식이 사사오입을 <b>정수 연산만으로</b> 내포해야 한다.
 */
class IntegerScoreTest {

    @Nested
    @DisplayName("f(k) = ⌊(2Wk + N) ÷ 2N⌋")
    class Formula {

        @Test
        @DisplayName("k=0 이면 0 — 아직 아무것도 확정되지 않았다")
        void zero() {
            assertThat(IntegerScore.f(6, 5, 0)).isZero();
        }

        @Test
        @DisplayName("W×k÷N 의 사사오입과 동치다 — 0.5 는 올린다")
        void isRoundHalfUp() {
            // 골드 주 4회: 1.5 → 2 (은행가 반올림이면 2 가 아니라 2… 다음 경계에서 갈린다)
            assertThat(IntegerScore.f(6, 4, 1)).isEqualTo(2);    // 1.5 → 2
            // 브론즈 주 4회: 2.5 → 3 (은행가 반올림이면 2)
            assertThat(IntegerScore.f(10, 4, 1)).isEqualTo(3);
            // 실버 주 6회 미달축: 1.333… → 1
            assertThat(IntegerScore.f(8, 6, 1)).isEqualTo(1);
        }

        @ParameterizedTest
        @EnumSource(value = Tier.class, names = {"BRONZE", "SILVER", "GOLD", "DIAMOND", "RUBY"})
        @DisplayName("f(N) = W — 주간 목표를 다 채우면 정책 표의 주간 총 배점과 정확히 같다")
        void reachesWeeklyTotal(Tier tier) {
            for (int n = 1; n <= 7; n++) {
                assertThat(IntegerScore.f(TierPoints.weeklyGain(tier), n, n))
                        .as("%s 주 %d회 성공축", tier, n)
                        .isEqualTo(TierPoints.weeklyGain(tier));
                assertThat(IntegerScore.f(TierPoints.weeklyPenalty(tier), n, n))
                        .as("%s 주 %d회 미달축", tier, n)
                        .isEqualTo(TierPoints.weeklyPenalty(tier));
            }
        }

        @Test
        @DisplayName("누계는 카운트만의 함수라 단조 증가한다 — 중복 처리해도 결과가 같은 이유")
        void monotonic() {
            for (int n = 1; n <= 7; n++) {
                int prev = 0;
                for (int k = 0; k <= n; k++) {
                    int now = IntegerScore.f(14, n, k);
                    assertThat(now).isGreaterThanOrEqualTo(prev);
                    prev = now;
                }
            }
        }

        @Test
        @DisplayName("정책 §4.4 예시 — 골드·주 5회의 일별 반영이 표와 같다")
        void goldFiveTimesExample() {
            int ws = TierPoints.weeklyGain(Tier.GOLD);      // 6
            int wm = TierPoints.weeklyPenalty(Tier.GOLD);   // 14
            int n = 5;

            // 성공 반영 누계 f(1)=1, f(2)=2, f(3)=4
            assertThat(IntegerScore.f(ws, n, 1)).isEqualTo(1);
            assertThat(IntegerScore.f(ws, n, 2)).isEqualTo(2);
            assertThat(IntegerScore.f(ws, n, 3)).isEqualTo(4);
            // 미달 반영 누계 g(1)=3, g(2)=6
            assertThat(IntegerScore.f(wm, n, 1)).isEqualTo(3);
            assertThat(IntegerScore.f(wm, n, 2)).isEqualTo(6);
            // 정산: 성공 +4, 미달 −6 → 사이클 순 −2
            assertThat(IntegerScore.f(ws, n, 3) - IntegerScore.f(wm, n, 2)).isEqualTo(-2);
        }

        @Test
        @DisplayName("2Wk 최댓값은 532 라 32비트 안에서 안전하다")
        void noOverflow() {
            assertThat(2 * TierPoints.weeklyPenalty(Tier.RUBY) * 7).isEqualTo(532);
        }
    }

    @Nested
    @DisplayName("사이클 순변동 ±20 클램핑 (정책 §4.7)")
    class Clamp {

        @Test
        @DisplayName("한도 안에서는 원점수가 그대로 반영된다")
        void withinLimit() {
            CycleLimit.Result r = CycleLimit.apply(5, 0, 0, 100);
            assertThat(r.limitedDelta()).isEqualTo(5);
            assertThat(r.appliedDelta()).isEqualTo(5);
            assertThat(r.rawCumulative()).isEqualTo(5);
            assertThat(r.limitedCumulative()).isEqualTo(5);
        }

        @Test
        @DisplayName("+25 까지 오르면 반영은 +20 에서 멈춘다")
        void capsAtPlusTwenty() {
            CycleLimit.Result r = CycleLimit.apply(25, 0, 0, 100);
            assertThat(r.limitedDelta()).isEqualTo(20);
            assertThat(r.rawCumulative()).isEqualTo(25);      // 원점수는 전액 누적
            assertThat(r.limitedCumulative()).isEqualTo(20);
        }

        @Test
        @DisplayName("원점수 +25 뒤 −5 — 순변동이 여전히 +20 이라 반영은 움직이지 않는다")
        void returningToCapDoesNotMove() {
            CycleLimit.Result r = CycleLimit.apply(-5, 25, 20, 120);
            assertThat(r.limitedDelta()).isZero();
            assertThat(r.appliedDelta()).isZero();
            assertThat(r.rawCumulative()).isEqualTo(20);
            assertThat(r.limitedCumulative()).isEqualTo(20);
        }

        @Test
        @DisplayName("원점수 +25 뒤 −10 — 순변동이 +15 로 내려오면 반영도 함께 내려온다")
        void comingBackInsideMoves() {
            CycleLimit.Result r = CycleLimit.apply(-10, 25, 20, 120);
            assertThat(r.limitedDelta()).isEqualTo(-5);
            assertThat(r.limitedCumulative()).isEqualTo(15);
        }

        @Test
        @DisplayName("루비 주 1회 전량 미달(이론 −38)은 −20 으로 잘린다")
        void rubyFullMissIsClamped() {
            int wm = TierPoints.weeklyPenalty(Tier.RUBY);
            CycleLimit.Result r = CycleLimit.apply(-IntegerScore.f(wm, 1, 1), 0, 0, 500);
            assertThat(r.limitedDelta()).isEqualTo(-20);
            assertThat(r.rawCumulative()).isEqualTo(-38);
        }

        @Test
        @DisplayName("누적 점수는 0~2,000 을 벗어나지 않는다")
        void accountRange() {
            assertThat(CycleLimit.apply(-10, 0, 0, 3).appliedDelta()).isEqualTo(-3);
            assertThat(CycleLimit.apply(10, 0, 0, TierBands.MAX_SCORE).appliedDelta()).isZero();
        }

        @Test
        @DisplayName("""
                0점에서 반영되지 않은 감점은 한도를 소비하지 않는다 —
                덮어쓰면 원점수가 회복될 때 받은 적 없는 점수가 지급된다""")
        void zeroFloorDoesNotConsumeLimit() {
            // 0점 계정에서 −10. 실제로는 점수가 움직이지 않는다.
            CycleLimit.Result first = CycleLimit.apply(-10, 0, 0, 0);
            assertThat(first.appliedDelta()).isZero();
            assertThat(first.rawCumulative()).isEqualTo(-10);   // 원점수는 기록한다
            assertThat(first.limitedCumulative()).isZero();     // 한도는 쓰지 않았다

            // 이어서 원점수가 +10 회복되면 순변동은 0 이다. 반영 누계도 0 이었으므로 지급도 0 이어야 한다.
            CycleLimit.Result second = CycleLimit.apply(10, first.rawCumulative(),
                    first.limitedCumulative(), 0);
            assertThat(second.appliedDelta()).isZero();
        }
    }

    @Nested
    @DisplayName("티어 구간과 표시 티어 (정책 §1.1 · §1.2)")
    class Tiers {

        @Test
        @DisplayName("구간 경계 — 99/100, 299/300, 499/500, 999/1000")
        void boundaries() {
            assertThat(TierBands.of(99)).isEqualTo(Tier.BRONZE);
            assertThat(TierBands.of(100)).isEqualTo(Tier.SILVER);
            assertThat(TierBands.of(299)).isEqualTo(Tier.SILVER);
            assertThat(TierBands.of(300)).isEqualTo(Tier.GOLD);
            assertThat(TierBands.of(499)).isEqualTo(Tier.GOLD);
            assertThat(TierBands.of(500)).isEqualTo(Tier.DIAMOND);
            assertThat(TierBands.of(999)).isEqualTo(Tier.DIAMOND);
            assertThat(TierBands.of(1000)).isEqualTo(Tier.RUBY);
            assertThat(TierBands.of(2000)).isEqualTo(Tier.RUBY);
        }

        @Test
        @DisplayName("유예 구간과 강등 확정선 — 정책 §1.1 표 그대로")
        void graceBands() {
            assertThat(TierBands.graceFloor(Tier.SILVER)).isEqualTo(80);
            assertThat(TierBands.demoteAt(Tier.SILVER)).isEqualTo(79);
            assertThat(TierBands.graceFloor(Tier.GOLD)).isEqualTo(280);
            assertThat(TierBands.demoteAt(Tier.GOLD)).isEqualTo(279);
            assertThat(TierBands.graceFloor(Tier.DIAMOND)).isEqualTo(480);
            assertThat(TierBands.demoteAt(Tier.DIAMOND)).isEqualTo(479);
            assertThat(TierBands.graceFloor(Tier.RUBY)).isEqualTo(980);
            assertThat(TierBands.demoteAt(Tier.RUBY)).isEqualTo(979);
        }

        @Test
        @DisplayName("승급은 유예 없이 즉시 — 실제 티어가 표시 티어 이상이면 그대로 올린다")
        void promotionIsImmediate() {
            assertThat(TierBands.displayTier(300, Tier.GOLD, Tier.SILVER)).isEqualTo(Tier.GOLD);
            // 승급하면 초과 점수를 버리지 않는다 — 98 +4 = 102 실버.
            assertThat(TierBands.of(102)).isEqualTo(Tier.SILVER);
        }

        @Test
        @DisplayName("유예 구간에서는 표시 티어를 유지한다")
        void graceKeepsDisplay() {
            assertThat(TierBands.displayTier(285, Tier.SILVER, Tier.GOLD)).isEqualTo(Tier.GOLD);
            assertThat(TierBands.isInGraceBand(285, Tier.GOLD)).isTrue();
        }

        @Test
        @DisplayName("큰 감점으로 유예를 한 번에 관통하면 실제 티어까지 즉시 강등한다")
        void bigDropSkipsGrace() {
            assertThat(TierBands.displayTier(279, Tier.BRONZE, Tier.GOLD)).isEqualTo(Tier.BRONZE);
            assertThat(TierBands.displayTier(50, Tier.BRONZE, Tier.DIAMOND)).isEqualTo(Tier.BRONZE);
        }

        @Test
        @DisplayName("브론즈는 유예 구간이 없다")
        void bronzeHasNoGrace() {
            assertThat(TierBands.isInGraceBand(0, Tier.BRONZE)).isFalse();
            assertThat(TierBands.hasDemotion(Tier.BRONZE)).isFalse();
        }
    }

    @Nested
    @DisplayName("연속 기록 보너스와 추가 감점 (정책 §4.6)")
    class Streaks {

        @Test
        @DisplayName("연속 성공 보너스는 티어가 높을수록 낮은 천장에서 멈춘다")
        void successBonus() {
            assertThat(TierPoints.streakBonus(Tier.BRONZE, 1)).isZero();
            assertThat(TierPoints.streakBonus(Tier.BRONZE, 2)).isEqualTo(1);
            assertThat(TierPoints.streakBonus(Tier.BRONZE, 5)).isEqualTo(4);
            assertThat(TierPoints.streakBonus(Tier.BRONZE, 9)).isEqualTo(5);   // 6사이클 이상 최대 +5

            assertThat(TierPoints.streakBonus(Tier.SILVER, 9)).isEqualTo(4);
            assertThat(TierPoints.streakBonus(Tier.GOLD, 9)).isEqualTo(3);
            assertThat(TierPoints.streakBonus(Tier.DIAMOND, 9)).isEqualTo(2);
            assertThat(TierPoints.streakBonus(Tier.RUBY, 9)).isEqualTo(1);
        }

        @Test
        @DisplayName("연속 실패 추가 감점은 티어와 무관하게 −3 에서 멈춘다")
        void failurePenalty() {
            assertThat(TierPoints.failurePenalty(1)).isZero();
            assertThat(TierPoints.failurePenalty(2)).isEqualTo(-1);
            assertThat(TierPoints.failurePenalty(3)).isEqualTo(-2);
            assertThat(TierPoints.failurePenalty(9)).isEqualTo(-3);
        }
    }
}
