package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.HealthConfig;
import com.ruleup.ruleup_backend.verification.domain.HealthMetric;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.signal.HealthOrigin;
import com.ruleup.ruleup_backend.verification.signal.HealthReading;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * 움직임(HEALTH) 평가기(테크스펙 v2 §8) — 도달형. Health Connect readings를 메타데이터 게이트로 거른 뒤
 * 대상 metric 합계 ≥ goal 이면 충족.
 *
 *  게이트(§8.2, 추가 데이터 0·서버 로직만):
 *   - recordingMethod == MANUAL  → 거부(손입력).
 *   - dataOrigin 화이트리스트 밖  → 거부(UNTRUSTED_HEALTH_SOURCE). (trustedOrigins 비면 게이트 미적용)
 *   - exerciseType 불일치(설정 시) → 집계 제외.
 *   - origin 누락                 → 거부(신뢰 게이트 입력 없음, §6.3).
 *
 *  <h4>겹치는 구간은 한 번만 센다</h4>
 *  폰과 워치가 같은 산책을 각자 기록하면 두 레코드의 구간이 겹친다. 그냥 더하면 두 배가 되고,
 *  큰 쪽만 쓰면 겹치지 않는 다른 구간을 통째로 버린다. 그래서 <b>구간이 있는 레코드는 서버가
 *  구간을 합집합으로 접어</b> 겹친 만큼 비례 배분해 더한다(스펙 「겹치는 동일 시간 구간은 한 번만 집계」).
 *  구간을 보내지 않는 예전 클라의 레코드는 「그날 누적값」으로 보고 가장 큰 값을 쓴다 — 둘이 섞여
 *  오면 더 큰 쪽을 판정값으로 삼는다.
 *
 *  결과값만 받으므로 좌표를 서버가 안 들고 있는다(§1.5와 일관). 작정한 어뷰징(루팅+위장 주입)은 MVP 범위 밖(§8.3).
 *  - rejected가 있고 통과분이 0이면 사유를 UNTRUSTED_HEALTH_SOURCE로 노출(전부 막힌 케이스). 그 외 미달은 마감 배치가 INSUFFICIENT_*.
 *  - 화이트리스트 밖 origin은 evidence.rejectedOrigins에 적재(나중에 하이브리드 켜기 판단 근거, §8.3).
 */
@Component
public class HealthEvaluator implements MethodEvaluator {

    @Override
    public VerificationMethod method() { return VerificationMethod.HEALTH; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        HealthConfig cfg = ctx.config().health();
        if (cfg == null) return EvaluationOutcome.pending(null, null);

        double goal = (cfg.goal() != null) ? cfg.goal().doubleValue() : 0;
        HealthMetric target = (cfg.metric() != null) ? cfg.metric() : HealthMetric.DISTANCE;
        // 그날 원본 전부를 매번 다시 읽는다 — 이월 없이 처음부터 집계한다.
        Set<String> rejected = new LinkedHashSet<>();

        boolean anyReading = false;
        boolean anyRejected = false;
        List<Measured> intervals = new ArrayList<>();   // 구간이 있는 레코드
        Set<String> seenRecords = new HashSet<>();
        double cumulativeMax = 0;                        // 구간이 없는 레코드(그날 누적값)

        if (ctx.signals() != null) {
            for (SyncSignal s : ctx.signals()) {
                if (!SignalType.HEALTH.name().equals(s.type()) || s.readings() == null) continue;
                for (HealthReading r : s.readings()) {
                    if (r == null || r.metric() == null) continue;
                    if (!r.metric().equalsIgnoreCase(target.name())) continue;       // 다른 지표는 무시
                    anyReading = true;
                    if (cfg.exerciseType() != null && r.exerciseType() != null
                            && !cfg.exerciseType().equalsIgnoreCase(r.exerciseType())) continue; // 운동종류 불일치 제외

                    String gate = gateReason(r, cfg);
                    if (gate != null) {
                        anyRejected = true;
                        HealthOrigin o = r.origin();
                        rejected.add((o != null && o.dataOrigin() != null ? o.dataOrigin() : "?") + ":" + gate);
                        continue;
                    }
                    double value = (r.value() != null) ? r.value().doubleValue() : 0;
                    Instant from = TimeWindows.parseInstant(r.startTime());
                    Instant to = TimeWindows.parseInstant(r.endTime());
                    if (from == null || to == null || !to.isAfter(from)) {
                        cumulativeMax = Math.max(cumulativeMax, value);   // 구간 없음 → 누적값으로 본다
                        continue;
                    }
                    // 같은 레코드가 두 번 남아 있으면(재전송) 한 번만 센다.
                    if (r.recordId() != null && !seenRecords.add(r.recordId())) continue;
                    intervals.add(new Measured(from, to, value));
                }
            }
        }

        double best = Math.max(cumulativeMax, dedupedSum(intervals));

        Map<String, Object> ev = new HashMap<>();
        ev.put("metric", target.name());
        ev.put("value", round2(best));
        ev.put("goal", goal);
        ev.put("unit", cfg.unit());
        if (!rejected.isEmpty()) ev.put("rejectedOrigins", new ArrayList<>(rejected));

        if (best >= goal && goal > 0) {
            return EvaluationOutcome.success(ev, windowClose(ctx));
        }
        // 미충족은 즉시 FAILED 잠그지 않는다(늦은 워치 데이터로 PENDING→SUCCESS 정정 허용, §7.4).
        // 통과분이 전무하고 거절만 있었으면 마감 사유를 UNTRUSTED_HEALTH_SOURCE로 고르도록 힌트만 남긴다(§8.2).
        if (best <= 0 && anyRejected && anyReading) ev.put("pendingReason", "UNTRUSTED_HEALTH_SOURCE");
        return EvaluationOutcome.pending(ev, windowClose(ctx));
    }

    /** 구간을 가진 측정값 1건. */
    private record Measured(Instant from, Instant to, double value) {}

    /**
     * 겹치는 구간을 걷어낸 합계.
     *
     * <p>레코드를 시작 시각으로 정렬해 훑으면서, 이미 집계한 구간과 <b>겹치지 않는 부분만큼만</b>
     * 비례해 더한다. 값이 구간에 고르게 분포한다고 보는 근사지만, 겹친 만큼을 통째로 두 번 세거나
     * 통째로 버리는 것보다 실제에 가깝다. Health Connect 는 같은 활동을 기기별로 따로 기록한다.
     */
    private double dedupedSum(List<Measured> readings) {
        if (readings.isEmpty()) return 0;
        List<Measured> sorted = new ArrayList<>(readings);
        sorted.sort(Comparator.comparing(Measured::from).thenComparing(Measured::to));

        double sum = 0;
        Instant coveredUntil = null;   // 여기까지는 이미 집계했다
        for (Measured m : sorted) {
            long span = m.to().getEpochSecond() - m.from().getEpochSecond();
            if (span <= 0) continue;
            Instant start = (coveredUntil != null && coveredUntil.isAfter(m.from())) ? coveredUntil : m.from();
            long fresh = m.to().getEpochSecond() - start.getEpochSecond();
            if (fresh <= 0) continue;                       // 완전히 겹친 레코드 — 더하지 않는다
            sum += m.value() * ((double) fresh / span);     // 겹치지 않은 비율만큼
            coveredUntil = m.to();
        }
        return sum;
    }

    /** 게이트 통과 여부. 통과면 null, 막히면 사유 문자열. */
    private String gateReason(HealthReading r, HealthConfig cfg) {
        HealthOrigin o = r.origin();
        if (o == null) return "NO_ORIGIN";                                   // §6.3 메타데이터 누락
        if (cfg.rejectManualRecording() && "MANUAL".equalsIgnoreCase(o.recordingMethod())) return "MANUAL";
        List<String> trusted = cfg.trustedOrigins();
        if (trusted != null && !trusted.isEmpty()) {
            boolean ok = o.dataOrigin() != null && trusted.contains(o.dataOrigin());
            if (!ok) return "UNTRUSTED_ORIGIN";
        }
        return null;
    }

    private Instant windowClose(DayContext ctx) {
        return TimeWindows.startOfDay(ctx.targetDate().plusDays(1), ctx.zone());
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
