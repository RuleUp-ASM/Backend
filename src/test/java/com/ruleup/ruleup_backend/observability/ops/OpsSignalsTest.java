package com.ruleup.ruleup_backend.observability.ops;

import com.ruleup.ruleup_backend.config.runtime.RuntimeRole;
import com.ruleup.ruleup_backend.config.runtime.RuntimeRoleProperties;
import com.ruleup.ruleup_backend.observability.metric.MetricContext;
import com.ruleup.ruleup_backend.observability.metric.MinuteMetricBuffer;
import com.ruleup.ruleup_backend.observability.metric.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OpsSignalsTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-10-11T10:00:00Z"));
    private final MinuteMetricBuffer buffer = new MinuteMetricBuffer(clock);
    private final OpsSignals ops = new OpsSignals(buffer,
            new MetricContext("prod", new RuntimeRoleProperties(RuntimeRole.API, false)));

    private Map<String, Double> drain() {
        clock.advanceSeconds(60);
        return buffer.drainCompleted().stream().filter(d -> d.value() != null).collect(Collectors.toMap(
                d -> d.key().namespace() + " " + d.key().name() + " " + d.key().sortedDimensions(),
                d -> d.value(), Double::sum));
    }

    @Test
    @DisplayName("공급자 호출은 시도마다 세고, 실패는 Feature·Provider 로 나뉜다(폴백 성공이어도 1차 실패는 실패)")
    void providerAttempts() {
        ops.providerCall("generation", "gemini", true, 900);
        ops.providerCall("generation", "nova", false, 1200);

        Map<String, Double> m = drain();
        assertThat(m).containsEntry("RuleUp/Ops ProviderCallCount {Env=prod, Feature=generation, Provider=gemini, Service=ruleup-api}", 1.0)
                .containsEntry("RuleUp/Ops ProviderFailureCount {Env=prod, Feature=generation, Provider=gemini, Service=ruleup-api}", 1.0)
                .containsEntry("RuleUp/Ops ProviderCallCount {Env=prod, Feature=generation, Provider=nova, Service=ruleup-api}", 1.0)
                .doesNotContainKey("RuleUp/Ops ProviderFailureCount {Env=prod, Feature=generation, Provider=nova, Service=ruleup-api}");
    }

    @Test
    @DisplayName("푸시는 토큰 단위로 시도·서버 오류·죽은 토큰을 나누고, 발송 처리량은 일이 없어도 0 이다")
    void pushTokens() {
        Map<String, Double> idle = drain();
        assertThat(idle).containsEntry("RuleUp/Traffic PushAttemptCount {Env=prod, Service=ruleup-api}", 0.0);

        ops.pushTokens(50, 3, 12);
        Map<String, Double> m = drain();
        assertThat(m).containsEntry("RuleUp/Traffic PushAttemptCount {Env=prod, Service=ruleup-api}", 50.0)
                .containsEntry("RuleUp/Ops ProviderFailureCount {Env=prod, Feature=push, Provider=fcm, Service=ruleup-api}", 3.0)
                .containsEntry("RuleUp/Ops InvalidTokenCount {Env=prod, Provider=fcm, Service=ruleup-api}", 12.0);
    }

    @Test
    @DisplayName("보안·불변식 카운터는 고정 차원만 쓴다")
    void securityAndInvariant() {
        ops.adminLoginFailed();
        ops.refreshReuseDetected();
        ops.invariantViolated(OpsSignals.Invariant.SCORE_LEDGER, 2);
        ops.invariantViolated(OpsSignals.Invariant.SCORE_LEDGER, 0);

        Map<String, Double> m = drain();
        assertThat(m).containsEntry("RuleUp/Security AdminLoginFailedCount {Env=prod, Service=ruleup-api}", 1.0)
                .containsEntry("RuleUp/Security RefreshReuseDetectedCount {Env=prod, Service=ruleup-api}", 1.0)
                .containsEntry("RuleUp/Ops InvariantViolationCount {Env=prod, Invariant=SCORE_LEDGER, Service=ruleup-api}", 2.0);
    }

    @Test
    @DisplayName("Old 영역·정지형 수집기 이름을 가린다")
    void gcNames() {
        assertThat(RuntimeOpsSampler.isOldGen("G1 Old Gen")).isTrue();
        assertThat(RuntimeOpsSampler.isOldGen("Tenured Gen")).isTrue();
        assertThat(RuntimeOpsSampler.isOldGen("G1 Eden Space")).isFalse();
        assertThat(RuntimeOpsSampler.isConcurrent("G1 Concurrent GC")).isTrue();
        assertThat(RuntimeOpsSampler.isConcurrent("G1 Young Generation")).isFalse();
    }
}
