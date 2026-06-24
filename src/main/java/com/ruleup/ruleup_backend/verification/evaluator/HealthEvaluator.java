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
        // HEALTH는 그날 누적 결과값을 통째로 다시 보내므로(델타 아님) prior 합산 대신 max로 갱신.
        double priorBest = priorValue(ctx.priorEvidence());
        Set<String> rejected = new LinkedHashSet<>(priorRejected(ctx.priorEvidence()));

        double passedSum = 0;
        boolean anyReading = false;
        boolean anyRejected = false;

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
                    passedSum += (r.value() != null) ? r.value().doubleValue() : 0;
                }
            }
        }

        double best = Math.max(priorBest, passedSum);

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

    private double priorValue(Map<String, Object> prior) {
        Object v = (prior != null) ? prior.get("value") : null;
        return (v instanceof Number n) ? n.doubleValue() : 0;
    }
    @SuppressWarnings("unchecked")
    private List<String> priorRejected(Map<String, Object> prior) {
        Object v = (prior != null) ? prior.get("rejectedOrigins") : null;
        return (v instanceof List<?> l) ? (List<String>) l : List.of();
    }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
