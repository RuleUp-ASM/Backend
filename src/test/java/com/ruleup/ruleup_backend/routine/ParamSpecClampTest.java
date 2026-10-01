package com.ruleup.ruleup_backend.routine;

import com.ruleup.ruleup_backend.routine.domain.ParamSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** LLM 목표값 보정 — "매일 20보 걷기"가 목표 10000보(기본값)로 튀면 안 된다. */
class ParamSpecClampTest {

    private final ParamSpec steps = ParamSpec.parse("steps",
            Map.of("min", 1000, "max", 100000, "default", 10000));

    @Test
    @DisplayName("최소보다 작으면 기본값이 아니라 최소값")
    void belowMinClampsToMin() {
        assertThat(steps.clampOrDefault("20")).isEqualTo(new BigDecimal("1000"));
    }

    @Test
    @DisplayName("최대보다 크면 최대값")
    void aboveMaxClampsToMax() {
        assertThat(steps.clampOrDefault("500000")).isEqualTo(new BigDecimal("100000"));
    }

    @Test
    @DisplayName("범위 안이면 그대로, 형식 오류·0 이하·누락은 기본값")
    void validOrDefault() {
        assertThat(steps.clampOrDefault("3000")).isEqualTo(new BigDecimal("3000"));
        assertThat(steps.clampOrDefault("많이")).isEqualTo(new BigDecimal("10000"));
        assertThat(steps.clampOrDefault("0")).isEqualTo(new BigDecimal("10000"));
        assertThat(steps.clampOrDefault(null)).isEqualTo(new BigDecimal("10000"));
    }

    @Test
    @DisplayName("프롬프트 표기에 허용 범위가 붙는다")
    void rangeLabel() {
        assertThat(steps.rangeLabel()).isEqualTo("steps(1000~100000)");
        assertThat(ParamSpec.parse("target_time", Map.of("default", "07:00", "unit", "hh:mm")).rangeLabel())
                .isEqualTo("target_time");
    }
}
