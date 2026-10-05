package com.ruleup.ruleup_backend.observability;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerificationRateMetricsTest {

    /** 2026-10-05 00:30 KST — UTC 로는 아직 10-04 다. 날짜를 KST 로 자르는지 함께 본다. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T15:30:00Z"), ZoneOffset.UTC);
    private final VerificationDailyRepository repository = mock(VerificationDailyRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final VerificationRateMetrics metrics = new VerificationRateMetrics(repository, clock, registry);

    private double gauge(String name, String status) {
        return registry.get(name).tag("status", status).gauge().value();
    }

    @Test
    @DisplayName("오늘(KST) 귀속분은 성공·미확정을, 확정 끝난 그제 귀속분은 성공·실패를 센다")
    void countsTodayAndFinalizedDay() {
        Instant today = VerificationDeadlines.finalizeAfter(LocalDate.of(2026, 10, 5));
        Instant finalized = VerificationDeadlines.finalizeAfter(LocalDate.of(2026, 10, 3));
        when(repository.countByStatusAndFinalizeAfter(VerificationStatus.SUCCESS, today)).thenReturn(7L);
        when(repository.countByStatusAndFinalizeAfter(VerificationStatus.PENDING, today)).thenReturn(13L);
        when(repository.countByStatusAndFinalizeAfter(VerificationStatus.SUCCESS, finalized)).thenReturn(40L);
        when(repository.countByStatusAndFinalizeAfter(VerificationStatus.FAILED, finalized)).thenReturn(10L);

        metrics.refresh();

        assertThat(gauge("biz.verification.today", "success")).isEqualTo(7);
        assertThat(gauge("biz.verification.today", "pending")).isEqualTo(13);
        assertThat(gauge("biz.verification.finalized", "success")).isEqualTo(40);
        assertThat(gauge("biz.verification.finalized", "failed")).isEqualTo(10);
    }

    @Test
    @DisplayName("집계가 실패해도 예외를 밖으로 내지 않고 직전 값을 유지한다")
    void failureKeepsPreviousValue() {
        when(repository.countByStatusAndFinalizeAfter(any(), any())).thenReturn(5L);
        metrics.refresh();
        when(repository.countByStatusAndFinalizeAfter(any(), any())).thenThrow(new IllegalStateException("db down"));

        metrics.refresh();

        assertThat(gauge("biz.verification.today", "success")).isEqualTo(5);
        assertThat(gauge("biz.verification.finalized", "failed")).isEqualTo(5);
    }
}
