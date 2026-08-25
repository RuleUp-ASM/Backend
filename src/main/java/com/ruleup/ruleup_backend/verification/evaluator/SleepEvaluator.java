package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.SleepConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
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

        if (segs.isEmpty()) {
            return EvaluationOutcome.pending(Map.of("note", "no_sleep_segments"), windowClose);
        }

        Instant bedtime = segs.stream().map(s -> TimeWindows.parseInstant(s.startAt()))
                .filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
        long sleepSec = 0;
        for (SleepSegment s : segs) {
            Instant st = TimeWindows.parseInstant(s.startAt());
            Instant en = TimeWindows.parseInstant(s.endAt());
            if (st != null && en != null && en.isAfter(st)) sleepSec += en.getEpochSecond() - st.getEpochSecond();
        }
        double sleepHours = sleepSec / 3600.0;

        Map<String, Object> ev = new HashMap<>();
        if (bedtime != null) ev.put("bedtime", bedtime.toString());
        ev.put("sleepHours", Math.round(sleepHours * 100.0) / 100.0);

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
    private Instant bedtimeThreshold(String hhmm, LocalDate targetDate, ZoneId zone) {
        LocalTime t = LocalTime.parse(hhmm);
        LocalDate d = (t.getHour() < 12) ? targetDate.plusDays(1) : targetDate;  // 00~11시 → 익일
        return ZonedDateTime.of(d, t, zone).toInstant();
    }
}
