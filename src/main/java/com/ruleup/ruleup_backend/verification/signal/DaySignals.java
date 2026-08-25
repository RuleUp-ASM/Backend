package com.ruleup.ruleup_backend.verification.signal;

import com.ruleup.ruleup_backend.verification.evaluator.TimeWindows;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 신호를 <b>발생 시각 기준</b>으로 하루에 귀속시킨다 (백엔드 테크스펙 §4-3 "날짜 귀속").
 *
 * <p>도착 시각이 아니라 발생 시각으로 가른다. sync 한 번에 어제치와 오늘치가 섞여 오는 일이 흔한데
 * (절전으로 밀린 구간, 자정 직후 전송, 오프라인 복구), 걸러내지 않으면 어제 다녀온 기록이 오늘 인증을
 * 통과시키거나 반대로 오늘 활동이 어제 판정에 얹힌다.
 *
 * <p>신호 하나가 날짜 경계를 걸치면 <b>항목 단위</b>로 자른다 — 지오펜스 전환·측위 포인트·앱 사용 이벤트는
 * 각자 발생 시각을 들고 있기 때문이다. 하루 단위로 선언된 신호({@code date})는 그 날짜에만 쓰고,
 * 수면은 "밤이 시작된 날짜"라는 별도 규칙이 있어 평가기가 직접 귀속시킨다.
 */
public final class DaySignals {

    private DaySignals() {}

    /** targetDate(KST)에 귀속되는 신호만 남긴다. 항목이 하나도 안 남는 신호는 통째로 뺀다. */
    public static List<SyncSignal> forDate(List<SyncSignal> signals, LocalDate targetDate, ZoneId zone) {
        if (signals == null || signals.isEmpty()) return List.of();
        Instant from = targetDate.atStartOfDay(zone).toInstant();
        Instant until = targetDate.plusDays(1).atStartOfDay(zone).toInstant();

        List<SyncSignal> out = new ArrayList<>();
        for (SyncSignal s : signals) {
            SyncSignal narrowed = narrow(s, targetDate, from, until);
            if (narrowed != null) out.add(narrowed);
        }
        return out;
    }

    private static SyncSignal narrow(SyncSignal s, LocalDate targetDate, Instant from, Instant until) {
        if (s == null || s.type() == null) return null;

        // 수면은 밤이 시작된 날짜에 귀속한다 — 자정을 넘기는 게 정상이라 여기서 자르면 안 된다.
        if (SignalType.SLEEP.name().equals(s.type())) return s;

        // 하루 단위로 선언된 신호(HEALTH 누적값 등)는 선언된 날짜에만 쓴다.
        if (s.date() != null && !s.date().isBlank()) {
            return targetDate.toString().equals(s.date().trim()) ? s : null;
        }

        List<GeofenceTransition> transitions = filter(s.transitions(), t -> at(t.at()), from, until);
        List<GeoPoint> points = filter(s.points(), p -> at(p.at()), from, until);
        List<UsageEvent> usageEvents = filter(s.usageEvents(), e -> at(e.at()), from, until);
        List<ScreenEvent> screenEvents = filter(s.screenEvents(), e -> at(e.at()), from, until);

        boolean hadItems = notEmpty(s.transitions()) || notEmpty(s.points())
                || notEmpty(s.usageEvents()) || notEmpty(s.screenEvents());
        if (hadItems) {
            boolean keepsAny = notEmpty(transitions) || notEmpty(points)
                    || notEmpty(usageEvents) || notEmpty(screenEvents);
            if (!keepsAny) return null;
            return new SyncSignal(s.type(), s.recordId(), s.observedAt(), transitions, points, s.isMock(),
                    s.readings(), s.sessionStart(), s.sessionEnd(), s.detectedActivity(), s.date(),
                    usageEvents, screenEvents, s.segments());
        }

        // 항목도 날짜 선언도 없는 신호(HEALTH readings 등)는 관측 시각으로 가른다.
        Instant observedAt = at(s.observedAt());
        if (observedAt == null) return null;   // 귀속시킬 근거가 없으면 판정에 쓰지 않는다
        return inRange(observedAt, from, until) ? s : null;
    }

    private static <T> List<T> filter(List<T> items, java.util.function.Function<T, Instant> timeOf,
                                      Instant from, Instant until) {
        if (items == null || items.isEmpty()) return items;
        List<T> kept = new ArrayList<>(items.size());
        for (T item : items) {
            Instant at = timeOf.apply(item);
            if (at != null && inRange(at, from, until)) kept.add(item);
        }
        return kept;
    }

    private static boolean inRange(Instant at, Instant from, Instant until) {
        return !at.isBefore(from) && at.isBefore(until);
    }

    private static boolean notEmpty(List<?> items) {
        return items != null && !items.isEmpty();
    }

    private static Instant at(String iso) {
        return TimeWindows.parseInstant(iso);
    }
}
