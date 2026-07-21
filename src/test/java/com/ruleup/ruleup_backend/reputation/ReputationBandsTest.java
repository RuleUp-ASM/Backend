package com.ruleup.ruleup_backend.reputation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 밴드 라벨 + 다음 목표(스펙 §6.1 예시값 정합). */
class ReputationBandsTest {

    @Test
    void bandLabel_75to80() {
        assertThat(ReputationBands.bandLabel(new BigDecimal("78.40")))
                .isEqualTo("4개를 1년 이상 or 2개를 3년 이상");
    }

    @Test
    void nextTier_78_40() {
        ReputationBands.NextTier nt = ReputationBands.nextTier(new BigDecimal("78.40"));
        assertThat(nt.target()).isEqualByComparingTo("80.00");
        assertThat(nt.progressRate()).isEqualByComparingTo("0.68");   // (78.4-75)/(80-75)
        assertThat(nt.label()).isEqualTo("80°C — 5개×1년 or 1개×5년 or 3개×3년");
    }

    @Test
    void initialTemp_band() {
        assertThat(ReputationBands.bandLabel(new BigDecimal("36.5"))).isEqualTo("노력하는 사람");
        ReputationBands.NextTier nt = ReputationBands.nextTier(new BigDecimal("36.5"));
        assertThat(nt.target()).isEqualByComparingTo("50.00");
    }

    @Test
    void topBand() {
        ReputationBands.NextTier nt = ReputationBands.nextTier(new BigDecimal("92.00"));
        assertThat(nt.target()).isEqualByComparingTo("100.00");
        assertThat(nt.progressRate()).isEqualByComparingTo("1.00");
    }
}
