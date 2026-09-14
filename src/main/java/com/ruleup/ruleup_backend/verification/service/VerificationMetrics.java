package com.ruleup.ruleup_backend.verification.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 인증 도메인 전용 지표 (백엔드 7절 모니터링).
 *
 * <p>스펙이 알림 조건을 <b>수치</b>로 적어 뒀는데 지표가 없으면 그 조건은 문서에만 존재한다.
 * 여기서 재는 것은 네 가지다.
 * <ul>
 *   <li><b>sync p95</b> — 목표 1초, 3초 초과 지속이면 알림. 백분위를 서버가 계산해 내보낸다
 *       (평균만 있으면 느린 꼬리가 평균에 묻힌다).</li>
 *   <li><b>dedup·게이트·동의 비율</b> — 급변이 곧 Android 재전송 버그·권한 변경·기기 이슈의 신호다.
 *       절대값이 아니라 <b>수신 신호 수 대비</b>로 봐야 해서 분모(수신 수)도 함께 센다.</li>
 *   <li><b>확정 배치</b> — 소요 시간과 처리 건수, 그리고 <b>03:30 이전에 끝났는지</b>.
 *       탐색 reconciliation 이 확정 결과를 입력으로 쓰므로 이 시각이 계약이다.</li>
 *   <li><b>확정 실패</b> — 스펙상 0건이어야 하는 값이라 1건이라도 세어져야 한다.</li>
 * </ul>
 */
@Component
public class VerificationMetrics {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 탐색 reconciliation 시각 — 확정 배치는 이보다 먼저 끝나야 한다. */
    private static final LocalTime RECONCILIATION_AT = LocalTime.of(3, 30);

    private final Timer syncTimer;
    private final Timer finalizeTimer;
    private final Counter signalsReceived;
    private final Counter signalsDeduped;
    private final Counter signalsGateDropped;
    private final Counter signalsConsentRejected;
    private final Counter finalized;
    private final Counter finalizeLate;
    private final Counter finalizeFailed;
    private final Counter deviceIdMissing;

    /** 마지막으로 확정 배치가 대상을 비운 시각(epoch millis). 0 이면 아직 돈 적이 없다. */
    private final AtomicLong lastFinalizeCompletedAt = new AtomicLong();

    public VerificationMetrics(MeterRegistry registry) {
        this.syncTimer = Timer.builder("verification.sync")
                .description("sync 처리 시간 — 목표 p95 1초")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.finalizeTimer = Timer.builder("verification.finalize.batch")
                .description("확정 배치 한 번이 대상을 비우는 데 걸린 시간")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        this.signalsReceived = Counter.builder("verification.signals.received")
                .description("수신 신호 수 — dedup·게이트 비율의 분모").register(registry);
        this.signalsDeduped = Counter.builder("verification.signals.deduped")
                .description("이미 받은 적이 있어 걸러낸 신호 수").register(registry);
        this.signalsGateDropped = Counter.builder("verification.signals.gate_dropped")
                .description("신뢰 게이트로 판정에서 뺀 신호 수").register(registry);
        this.signalsConsentRejected = Counter.builder("verification.signals.consent_rejected")
                .description("개별 동의가 없어 수집을 거부한 신호 수").register(registry);
        this.finalized = Counter.builder("verification.finalize.confirmed")
                .description("확정 배치가 종결한 판정 수").register(registry);
        this.finalizeLate = Counter.builder("verification.finalize.late")
                .description("03:30 탐색 reconciliation 이후까지 이어진 확정 배치 실행 수")
                .register(registry);
        // 스펙상 0 이어야 하는 값이라 1건이라도 세어져야 한다. 격리된 건은 조용히 미뤄지므로
        // 이 카운터가 없으면 「확정되지 않은 채 계속 밀리는 판정」을 아무도 모른다.
        this.finalizeFailed = Counter.builder("verification.finalize.failed")
                .description("대상 단위 확정 실패 — 격리 후 뒤로 미뤄진 판정 수").register(registry);
        // 활성 기기 검증을 엄격 모드로 켤 수 있는 시점을 이 값이 알려 준다. 0 이 되기 전에 켜면
        // 기기를 안 보내는 구버전 앱이 전부 인증 불가가 된다.
        this.deviceIdMissing = Counter.builder("verification.sync.device_id_missing")
                .description("기기 식별자 없이 들어온 sync 요청 수").register(registry);
        registry.gauge("verification.finalize.last_completed_epoch_ms", lastFinalizeCompletedAt,
                AtomicLong::doubleValue);
    }

    /** sync 한 번의 처리 시간과 신호 구성. */
    public void sync(long elapsedNanos, int received, int deduped, int gateDropped, int consentRejected) {
        syncTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
        if (received > 0) signalsReceived.increment(received);
        if (deduped > 0) signalsDeduped.increment(deduped);
        if (gateDropped > 0) signalsGateDropped.increment(gateDropped);
        if (consentRejected > 0) signalsConsentRejected.increment(consentRejected);
    }

    /** 기기 식별자 없이 sync 가 들어왔다(관대 모드에서만 도달한다). */
    public void deviceIdMissing() {
        deviceIdMissing.increment();
    }

    /** 한 건의 확정이 실패해 격리·연기됐다. */
    public void finalizeFailed() {
        finalizeFailed.increment();
    }

    /**
     * 확정 배치가 대상을 한 번 비웠다.
     *
     * <p>완료 시각이 03:30 을 넘겼으면 따로 센다 — 확정 결과가 탐색 reconciliation 의 입력이라,
     * 늦으면 완주율·유지율이 하루 밀린 값으로 계산된다.
     */
    public void finalizeBatch(int confirmed, long elapsedNanos) {
        finalizeTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
        if (confirmed > 0) finalized.increment(confirmed);
        Instant now = Instant.now();
        lastFinalizeCompletedAt.set(now.toEpochMilli());
        if (pastReconciliation(now)) finalizeLate.increment();
    }

    /**
     * 오늘 00시 확정분이 03:30 을 넘겨 처리되고 있는지.
     *
     * <p>00:00~03:30 사이의 실행만 「그날 확정분」이다. 그 뒤의 실행은 배치가 밀렸거나 늦게 도착한
     * 대상이며, 어느 쪽이든 reconciliation 이 이미 지나간 뒤라 같은 문제를 뜻한다.
     */
    private boolean pastReconciliation(Instant now) {
        var kstNow = now.atZone(KST);
        LocalDate today = kstNow.toLocalDate();
        return kstNow.toInstant().isAfter(today.atTime(RECONCILIATION_AT).atZone(KST).toInstant())
                && Duration.between(today.atStartOfDay(KST).toInstant(), kstNow.toInstant())
                        .compareTo(Duration.ofHours(12)) < 0;
    }
}
