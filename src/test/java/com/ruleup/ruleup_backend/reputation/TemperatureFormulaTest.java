package com.ruleup.ruleup_backend.reputation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 온도 공식 순수 단위 검증(테크스펙 §1.1·§2). Spring 불필요 — 기본 상수로 앵커·밴드 표를 재현한다.
 */
class TemperatureFormulaTest {

    private final TemperatureFormula formula = new TemperatureFormula(new ReputationTemperatureProperties());

    @Test
    @DisplayName("페이스 점수 c = clamp((r−0.75)/0.25, −1, +1)")
    void paceScore() {
        assertThat(formula.paceScore(1.00)).isEqualTo(1.0);    // 완벽
        assertThat(formula.paceScore(0.90)).isCloseTo(0.6, within(1e-9));   // 완주 커트라인
        assertThat(formula.paceScore(0.75)).isEqualTo(0.0);    // 중립 피벗
        assertThat(formula.paceScore(0.60)).isCloseTo(-0.6, within(1e-9));  // 노력하나 못 지킴
        assertThat(formula.paceScore(0.50)).isEqualTo(-1.0);   // 방치(하한 clamp)
        assertThat(formula.paceScore(0.30)).isEqualTo(-1.0);   // 하한 유지
    }

    @Test
    @DisplayName("밴드 매핑 f(G) 앵커가 스펙 표와 일치(연속·단조)")
    void bandAnchors() {
        assertThat(t(0)).isEqualTo(36.50);
        assertThat(t(1)).isEqualTo(50.00);
        assertThat(t(2)).isEqualTo(60.00);
        assertThat(t(3)).isEqualTo(70.00);
        assertThat(t(4)).isEqualTo(75.00);
        assertThat(t(5)).isEqualTo(80.00);
        assertThat(t(6)).isEqualTo(85.00);
        assertThat(t(7)).isEqualTo(87.50);
        assertThat(t(8)).isEqualTo(90.00);
    }

    @Test
    @DisplayName("하한 5 / 상한 93 clamp, 음수 구간·중간값 재현")
    void boundsAndMidpoints() {
        assertThat(t(-1)).isEqualTo(27.50);    // 방 1개 방치
        assertThat(t(-2)).isEqualTo(18.50);    // 죽은 방 2개
        assertThat(t(-3)).isEqualTo(9.50);     // 죽은 방 3개
        assertThat(t(-3.5)).isEqualTo(5.00);   // 하한
        assertThat(t(-10)).isEqualTo(5.00);    // 하한 clamp
        assertThat(t(10)).isEqualTo(93.00);    // 상한 clamp (90+1.5*2=93)
        assertThat(t(12)).isEqualTo(93.00);    // 상한 유지
        assertThat(t(0.12)).isCloseTo(38.12, within(0.01));   // 노력형 r=0.78
        assertThat(t(0.4)).isCloseTo(41.90, within(0.01));    // 적당히 r=0.85
    }

    @Test
    @DisplayName("f는 단조 증가")
    void monotonic() {
        double prev = -999;
        for (double g = -4; g <= 12; g += 0.1) {
            double cur = formula.temperature(g).doubleValue();
            assertThat(cur).isGreaterThanOrEqualTo(prev);
            prev = cur;
        }
    }

    private double t(double g) {
        return formula.temperature(g).doubleValue();
    }
}
