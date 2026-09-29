package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「처리되지 않은 인증」 — 확정 시각이 {@link #OVERDUE_AFTER} 넘게 지났는데 아직 확정되지 않은 판정 수.
 *
 * <p>확정 배치는 1분마다 돈다. {@code finalize.failed} 는 건별 실패만 센다. 배치가 아예 돌지 않으면
 * (락 고착·스케줄러 정지) 그 카운터는 0 으로 조용하다 — 그 경우를 잡는 게 이 게이지다.
 *
 * <p>{@link com.ruleup.ruleup_backend.common.outbox.OutboxMetrics} 처럼 5분마다 미리 세어 두고 게이지는
 * 그 값만 읽는다.
 */
@Slf4j
@Component
public class VerificationBacklogMetrics {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 확정 시각 이후 이만큼은 배치가 따라잡는 시간으로 본다(1분 주기 + 대량일 때 여러 번 비우기). */
    public static final Duration OVERDUE_AFTER = Duration.ofHours(1);

    private final VerificationDailyRepository repository;
    private final Clock clock;
    private final AtomicLong overdue = new AtomicLong();

    public VerificationBacklogMetrics(VerificationDailyRepository repository, Clock clock, MeterRegistry registry) {
        this.repository = repository;
        this.clock = clock;
        registry.gauge("verification.finalize.overdue", overdue, AtomicLong::doubleValue);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            LocalDate maxTargetDate = LocalDate.now(clock.withZone(KST)).minusDays(1L + VerificationDeadlines.GRACE_DAYS);
            overdue.set(repository.countOverduePending(clock.instant().minus(OVERDUE_AFTER), maxTargetDate));
        } catch (RuntimeException e) {
            // 지표 갱신 실패가 배치를 막으면 안 된다. 다음 주기가 다시 센다.
            log.warn("미확정 판정 지표 갱신 실패: {}", e.toString());
        }
    }
}
