package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.SleepConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
import com.ruleup.ruleup_backend.verification.signal.HealthOrigin;
import com.ruleup.ruleup_backend.verification.signal.SleepSegment;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/**
 * SLEEP 평가기 (§2.17) — 도달형. 익일 아침 도착 세그먼트로 판정.
 *  - 자정 넘김 귀속: start가 18:00~익일06:00이면 그 "저녁의 날짜"에 귀속 → targetDate 밤 세그먼트만 본다.
 *  - 판정: bedtimeBefore("HH:mm") → 취침(첫 start) ≤ 목표 / minSleepHours → 수면합 ≥ 목표.
 *  - 데이터는 회고적(밤은 이미 끝남)이라 세그먼트 도착 시 확정(SUCCESS/FAILED), 없으면 PENDING.
 */
@Component
public class SleepEvaluator implements MethodEvaluator {

    @Override
    public VerificationMethod method() { return VerificationMethod.SLEEP; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        SleepConfig cfg = ctx.config().sleep();
        if (cfg == null) return EvaluationOutcome.pending(null, null);

        ZoneId zone = ctx.zone();
        List<SleepSegment> segs = nightSegments(ctx.signals(), ctx.targetDate(), zone);
        Instant windowClose = TimeWindows.startOfDay(ctx.targetDate().plusDays(1), zone)
                .plus(Duration.ofHours(6));   // 익일 06:00경 도착 기대

        // 수면 세그먼트는 여러 sync 에 나뉘어 도착한다(기상 후 일괄 기록 → 절전으로 분할 전송).
        // 앞서 받은 구간을 잊으면 목표 시간을 영영 못 채우므로 evidence 에 누적하고,
        // 같은 구간이 재전송돼도 두 번 세지 않도록 (start|end) 키로 멱등 처리한다.
        LinkedHashSet<String> seen = new LinkedHashSet<>(priorSeen(ctx.priorEvidence()));
        long sleepSec = priorSeconds(ctx.priorEvidence());
        Instant bedtime = priorBedtime(ctx.priorEvidence());
        boolean anyUntrusted = priorUntrusted(ctx.priorEvidence());

        for (SleepSegment s : segs) {
            Instant st = TimeWindows.parseInstant(s.startAt());
            Instant en = TimeWindows.parseInstant(s.endAt());
            if (st == null || en == null || !en.isAfter(st)) continue;
            if (!seen.add(st.toString() + "|" + en.toString())) continue;   // 재전송 — 이미 반영했다
            if (!trusted(s)) { anyUntrusted = true; continue; }             // 손입력·비신뢰 출처는 제외
            sleepSec += en.getEpochSecond() - st.getEpochSecond();
            if (bedtime == null || st.isBefore(bedtime)) bedtime = st;
        }

        if (sleepSec == 0 && bedtime == null) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("note", anyUntrusted ? "untrusted_sleep_only" : "no_sleep_segments");
            if (!seen.isEmpty()) empty.put("seenSegments", new ArrayList<>(seen));
            if (anyUntrusted) empty.put("untrustedExcluded", true);
            return EvaluationOutcome.pending(empty, windowClose);
        }

        double sleepHours = sleepSec / 3600.0;

        Map<String, Object> ev = new HashMap<>();
        if (bedtime != null) ev.put("bedtime", bedtime.toString());
        ev.put("sleepHours", Math.round(sleepHours * 100.0) / 100.0);
        ev.put("sleepSeconds", sleepSec);
        if (!seen.isEmpty()) ev.put("seenSegments", new ArrayList<>(seen));
        if (anyUntrusted) ev.put("untrustedExcluded", true);

        // bedtimeBefore 판정(우선) → SLEPT_LATE
        if (cfg.bedtimeBefore() != null && bedtime != null) {
            Instant threshold = bedtimeThreshold(cfg.bedtimeBefore(), ctx.targetDate(), zone);
            ev.put("bedtimeBefore", cfg.bedtimeBefore());
            return bedtime.isAfter(threshold)
                    ? EvaluationOutcome.violated("SLEPT_LATE", ev, windowClose)
                    : EvaluationOutcome.success(ev, windowClose);
        }
        // minSleepHours 판정 → INSUFFICIENT_SLEEP
        if (cfg.minSleepHours() != null) {
            double goal = cfg.minSleepHours().doubleValue();
            ev.put("minSleepHours", goal);
            return (sleepHours >= goal)
                    ? EvaluationOutcome.success(ev, windowClose)
                    : EvaluationOutcome.violated("INSUFFICIENT_SLEEP", ev, windowClose);
        }
        return EvaluationOutcome.pending(ev, windowClose);
    }

    /** targetDate의 "밤"에 귀속되는 세그먼트(§2.17 자정 넘김 규칙). */
    private List<SleepSegment> nightSegments(List<SyncSignal> signals, LocalDate targetDate, ZoneId zone) {
        List<SleepSegment> out = new ArrayList<>();
        if (signals == null) return out;
        for (SyncSignal s : signals) {
            if (!SignalType.SLEEP.name().equals(s.type()) || s.segments() == null) continue;
            for (SleepSegment seg : s.segments()) {
                Instant st = TimeWindows.parseInstant(seg.startAt());
                if (st == null) continue;
                ZonedDateTime z = st.atZone(zone);
                LocalDate nightDate = (z.getHour() >= 18)
                        ? z.toLocalDate()                    // 저녁 → 그날
                        : z.toLocalDate().minusDays(1);      // 새벽(<18시) → 전날 밤
                if (nightDate.equals(targetDate)) out.add(seg);
            }
        }
        return out;
    }

    /** "HH:mm" 취침 마감 → 귀속 규칙에 맞춰 instant(자정 전이면 익일). */
    /**
     * 판정에 쓸 수 있는 수면 기록인지 (테크 스펙 §5-1 "신뢰 가능한 Health Connect 수면 기록만 사용").
     *
     * <p>손으로 입력한(MANUAL) 기록은 제외한다 — 자고 나서 적어 넣으면 인증이 통과되면 자동 인증이 아니다.
     * {@code origin} 을 아직 보내지 않는 클라가 있어 <b>없으면 통과</b>시키되 evidence 에 남긴다.
     * 실제 전송률을 관측한 뒤 "없으면 제외"로 조인다.
     */
    private boolean trusted(SleepSegment s) {
        HealthOrigin origin = s.origin();
        if (origin == null) return true;                                        // 미전송 — 관측 후 조인다
        return !"MANUAL".equalsIgnoreCase(origin.recordingMethod());
    }

    private long priorSeconds(Map<String, Object> prior) {
        Object v = (prior != null) ? prior.get("sleepSeconds") : null;
        return (v instanceof Number n) ? n.longValue() : 0;
    }

    private Instant priorBedtime(Map<String, Object> prior) {
        Object v = (prior != null) ? prior.get("bedtime") : null;
        return (v != null) ? TimeWindows.parseInstant(v.toString()) : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> priorSeen(Map<String, Object> prior) {
        Object v = (prior != null) ? prior.get("seenSegments") : null;
        return (v instanceof List<?> l) ? (List<String>) (List<?>) l : List.of();
    }

    private boolean priorUntrusted(Map<String, Object> prior) {
        Object v = (prior != null) ? prior.get("untrustedExcluded") : null;
        return Boolean.TRUE.equals(v);
    }

    private Instant bedtimeThreshold(String hhmm, LocalDate targetDate, ZoneId zone) {
        LocalTime t = LocalTime.parse(hhmm);
        LocalDate d = (t.getHour() < 12) ? targetDate.plusDays(1) : targetDate;  // 00~11시 → 익일
        return ZonedDateTime.of(d, t, zone).toInstant();
    }
}
