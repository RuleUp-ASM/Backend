package com.ruleup.ruleup_backend.observability;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 인증 성공률 — 귀속일의 판정 행을 상태별로 센 게이지 (노션 「RuleUp 모니터링」 2절 서비스 지표).
 * 비율은 대시보드가 계산한다. 서버가 비율을 내보내면 분모가 0 인 날과 진짜 0% 인 날을 구분할 수 없다.
 *
 * <p>날짜가 <b>둘</b>인 이유: 실패는 귀속일 이틀 뒤 00:00 KST 확정 배치에서만 생긴다
 * ({@link VerificationDeadlines}). 그래서 오늘 날짜만 세면 FAILED 는 늘 0 이고 성공률은 늘 100% 다.
 * <ul>
 *   <li>{@code biz.verification.today} — 오늘 귀속분. {@code success / (success + pending)} 이 「지금까지 해낸
 *       비율」이다. 행은 앱이 sync·수동 체크를 보낸 멤버에게만 열리므로 분모는 「오늘 움직인 사람」이다.</li>
 *   <li>{@code biz.verification.finalized} — 확정이 끝난 가장 최근 귀속일(그제). {@code success / (success + failed)}
 *       가 진짜 인증 성공률이다. 자정 직후 확정 배치가 비우기 전에는 PENDING 이 남아 잠시 작게 보인다 —
 *       그 지연은 {@code verification.finalize.overdue} 가 따로 잡는다.</li>
 * </ul>
 *
 * <p>{@link com.ruleup.ruleup_backend.verification.service.VerificationBacklogMetrics} 처럼 5분마다 미리 세어 두고
 * 게이지는 그 값만 읽는다. 읽기 전용 집계라 태스크마다 같은 값을 내므로 ShedLock 을 걸지 않는다.
 */
@Slf4j
@Component
public class VerificationRateMetrics {

    private final VerificationDailyRepository repository;
    private final Clock clock;
    private final AtomicLong todaySuccess = new AtomicLong();
    private final AtomicLong todayPending = new AtomicLong();
    private final AtomicLong finalizedSuccess = new AtomicLong();
    private final AtomicLong finalizedFailed = new AtomicLong();

    public VerificationRateMetrics(VerificationDailyRepository repository, Clock clock, MeterRegistry registry) {
        this.repository = repository;
        this.clock = clock;
        gauge(registry, "biz.verification.today", "success", todaySuccess,
                "오늘 귀속분 중 성공한 판정 수");
        gauge(registry, "biz.verification.today", "pending", todayPending,
                "오늘 귀속분 중 아직 성공 전인(미확정) 판정 수");
        gauge(registry, "biz.verification.finalized", "success", finalizedSuccess,
                "확정이 끝난 최근 귀속일(그제)의 성공 판정 수");
        gauge(registry, "biz.verification.finalized", "failed", finalizedFailed,
                "확정이 끝난 최근 귀속일(그제)의 실패 판정 수");
    }

    private static void gauge(MeterRegistry registry, String name, String status, AtomicLong value, String description) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .description(description)
                .tag("status", status)
                .register(registry);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            LocalDate today = LocalDate.now(clock.withZone(VerificationDeadlines.KST));
            Instant todayDeadline = VerificationDeadlines.finalizeAfter(today);
            todaySuccess.set(repository.countByStatusAndFinalizeAfter(VerificationStatus.SUCCESS, todayDeadline));
            todayPending.set(repository.countByStatusAndFinalizeAfter(VerificationStatus.PENDING, todayDeadline));

            Instant finalizedDeadline = VerificationDeadlines.finalizeAfter(today.minusDays(1L + VerificationDeadlines.GRACE_DAYS));
            finalizedSuccess.set(repository.countByStatusAndFinalizeAfter(VerificationStatus.SUCCESS, finalizedDeadline));
            finalizedFailed.set(repository.countByStatusAndFinalizeAfter(VerificationStatus.FAILED, finalizedDeadline));
        } catch (RuntimeException e) {
            // 지표 갱신 실패가 아무것도 막으면 안 된다. 다음 주기가 다시 센다.
            log.warn("인증 성공률 지표 갱신 실패: {}", e.toString());
        }
    }
}
