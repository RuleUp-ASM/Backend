package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.config.VerificationProperties;
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

    private final VerificationProperties properties;

    public SleepEvaluator(VerificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public VerificationMethod method() { return VerificationMethod.SLEEP; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        SleepConfig cfg = ctx.config().sleep();
        if (cfg == null) return EvaluationOutcome.pending(null, null);

        ZoneId zone = ctx.zone();
        List<NightSegment> segs = nightSegments(ctx.signals(), ctx.targetDate(), zone);
        Instant windowClose = TimeWindows.startOfDay(ctx.targetDate().plusDays(1), zone)
                .plus(Duration.ofHours(6));   // 익일 06:00경 도착 기대

        // 수면 세그먼트는 여러 sync 에 나뉘어 도착한다(기상 후 일괄 기록 → 절전으로 분할 전송).
        // 그날 원본을 통째로 받아 매번 처음부터 합산하고, 같은 구간이 두 행으로 남아 있어도
        // (start|end) 키로 한 번만 센다.
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        long sleepSec = 0;
        Instant bedtime = null;
        boolean anyUntrusted = false;
        int originMissing = 0;
        int future = 0;

        for (NightSegment night : segs) {
            SleepSegment s = night.segment();
            Instant st = TimeWindows.parseInstant(s.startAt());
            Instant en = TimeWindows.parseInstant(s.endAt());
            if (st == null || en == null || !en.isAfter(st)) continue;
            // <b>아직 오지 않은 잠은 잔 잠이 아니다.</b> 01:05 에 「22:10~06:30」 을 올리면 그 자리에서
            // DONE 이 됐다(QA SIG-10). 기준 시각은 <b>지금이 아니라 그 기록을 받은 때</b>다 —
            // 현재 시각으로 재면 즉시 평가에서만 걸리고, 시간이 지나 마감 배치가 같은 원본을 다시
            // 읽을 때는 더 이상 미래가 아니라서 새 기록 없이도 성공 근거로 되살아난다.
            // 실제로 자고 나면 같은 구간이 <b>나중 수신 시각</b>으로 다시 올라오므로 그때 인정된다.
            if (en.isAfter(claimedAt(night.receivedAt(), ctx).plus(CLOCK_SKEW))) { future++; continue; }
            if (!seen.add(st.toString() + "|" + en.toString())) continue;   // 재전송 — 이미 반영했다
            if (s.origin() == null) originMissing++;
            if (!trusted(s, cfg)) { anyUntrusted = true; continue; }             // 손입력·비신뢰 출처는 제외
            sleepSec += en.getEpochSecond() - st.getEpochSecond();
            if (bedtime == null || st.isBefore(bedtime)) bedtime = st;
        }

        if (sleepSec == 0 && bedtime == null) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("note", anyUntrusted ? "untrusted_sleep_only" : "no_sleep_segments");
            if (!seen.isEmpty()) empty.put("seenSegments", new ArrayList<>(seen));
            if (anyUntrusted) empty.put("untrustedExcluded", true);
            if (originMissing > 0) empty.put("originMissing", originMissing);
            if (future > 0) empty.put("excludedFuture", future);
            return EvaluationOutcome.pending(empty, windowClose);
        }

        double sleepHours = sleepSec / 3600.0;

        Map<String, Object> ev = new HashMap<>();
        if (bedtime != null) ev.put("bedtime", bedtime.toString());
        ev.put("sleepHours", Math.round(sleepHours * 100.0) / 100.0);
        ev.put("sleepSeconds", sleepSec);
        if (!seen.isEmpty()) ev.put("seenSegments", new ArrayList<>(seen));
        if (anyUntrusted) ev.put("untrustedExcluded", true);
        // 출처 없이 들어온 수면 기록 수. 엄격 모드를 켤 수 있는 시점을 이 값이 알려 준다.
        if (originMissing > 0) ev.put("originMissing", originMissing);
        if (future > 0) ev.put("excludedFuture", future);

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

    /**
     * 그 밤에 귀속되는 세그먼트와 <b>그것이 실린 신호의 수신 시각</b>.
     *
     * <p>세그먼트만 떼어 내면 「언제 받은 기록인가」를 잃는다 — 미래 구간 판정이 그 값을 쓴다.
     */
    private record NightSegment(SleepSegment segment, Instant receivedAt) {}

    /** targetDate의 "밤"에 귀속되는 세그먼트(§2.17 자정 넘김 규칙). */
    private List<NightSegment> nightSegments(List<SyncSignal> signals, LocalDate targetDate, ZoneId zone) {
        List<NightSegment> out = new ArrayList<>();
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
                if (nightDate.equals(targetDate)) out.add(new NightSegment(seg, s.receivedAt()));
            }
        }
        return out;
    }

    /** "HH:mm" 취침 마감 → 귀속 규칙에 맞춰 instant(자정 전이면 익일). */
    /**
     * 판정에 쓸 수 있는 수면 기록인지 (테크 스펙 §5-1 "신뢰 가능한 Health Connect 수면 기록만 사용").
     *
     * <p>손으로 입력한(MANUAL) 기록은 제외한다 — 자고 나서 적어 넣은 기록으로 인증이 통과되면
     * 자동 인증이 아니다.
     *
     * <p>{@code origin} 누락 처리는 <b>설정으로 가른다</b>. 걸음·거리(HEALTH)는 이미 출처가 없으면
     * 거부하는데 수면만 통과시키는 것은 일관되지 않다. 다만 지금 바로 조이면 출처를 보내지 않는
     * 클라의 수면 인증이 전부 막히므로, {@code app.verification.require-signal-origin} 으로 가른다.
     *
     * <p>출처가 <b>있어도</b> 화이트리스트를 본다. MANUAL 만 걸러서는 임의 앱이 AUTO 로 써 넣은
     * 기록이 그대로 통과해 「신뢰 가능한 Health Connect 수면 기록만 사용」이 지켜지지 않는다.
     * 걸음·거리가 이미 같은 게이트를 쓰고 있어, 수면만 열어 두는 것은 일관되지도 않다.
     * 목록이 비어 있으면 게이트를 적용하지 않는다 — 설정 미비가 곧 전면 차단이 되면 안 된다.
     */
    private boolean trusted(SleepSegment s, SleepConfig cfg) {
        HealthOrigin origin = s.origin();
        if (origin == null) return !properties.requireSignalOrigin();
        if ("MANUAL".equalsIgnoreCase(origin.recordingMethod())) return false;
        List<String> allow = (cfg != null) ? cfg.trustedOrigins() : null;
        if (allow == null || allow.isEmpty()) return true;
        return origin.dataOrigin() != null && allow.contains(origin.dataOrigin());
    }

    /**
     * 이 기록이 <b>주장된 시각</b> — 수신 시각과 현재 중 이른 쪽.
     *
     * <p>수신 시각은 요청 본문으로도 들어올 수 있어 그대로 믿지 않는다. 현재로 상한을 걸면
     * 미래 시각을 적어 보내도 이득이 없고, 저장된 값(항상 과거)은 그대로 쓰인다.
     */
    private static Instant claimedAt(Instant receivedAt, DayContext ctx) {
        return (receivedAt == null || receivedAt.isAfter(ctx.now())) ? ctx.now() : receivedAt;
    }

    /** 기기 시계가 조금 빠른 경우까지 미래로 몰지 않기 위한 허용치. */
    private static final Duration CLOCK_SKEW =
            Duration.ofSeconds(com.ruleup.ruleup_backend.common.ClockSkew.TOLERANCE_SECONDS);

    private Instant bedtimeThreshold(String hhmm, LocalDate targetDate, ZoneId zone) {
        LocalTime t = LocalTime.parse(hhmm);
        LocalDate d = (t.getHour() < 12) ? targetDate.plusDays(1) : targetDate;  // 00~11시 → 익일
        return ZonedDateTime.of(d, t, zone).toInstant();
    }
}
