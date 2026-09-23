package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.ScreenTimeConfig;
import com.ruleup.ruleup_backend.verification.domain.ScreenTimeMode;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import com.ruleup.ruleup_backend.verification.signal.UsageEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * SCREEN_TIME 평가기 (§2.13). usageEvents(RESUMED/PAUSED) 페어링 → 시간창 교집합 합산.
 *  - MIN(도달형): 창 내 사용 ≥ goalMinutes → SUCCESS. 미달이면 PENDING(창 닫힘 시 배치가 FAILED·INSUFFICIENT_USAGE).
 *  - MAX(제약형): 창 내 사용 > goalMinutes → 즉시 FAILED·USAGE_EXCEEDED. 무위반이면 PENDING(배치가 SUCCESS).
 *  - 전량 재평가: 그날 원본을 통째로 받아 매번 처음부터 페어링한다. 요약을 이월하지 않으므로
 *    분할 전송이 뒤바뀌어 PAUSED 가 먼저 도착해도 앞 이벤트가 버려지지 않는다(백엔드 4-3).
 *  - targetPackages 비면 전체 앱 사용으로 간주(폰 금지창 등).
 */
@Component
public class ScreenTimeEvaluator implements MethodEvaluator {

    @Override
    public VerificationMethod method() { return VerificationMethod.SCREEN_TIME; }

    @Override
    public EvaluationOutcome evaluate(DayContext ctx) {
        ScreenTimeConfig cfg = ctx.config().screenTime();
        if (cfg == null) return EvaluationOutcome.pending(null, null);

        Window window = resolveWindow(cfg.timeWindow(), ctx.targetDate(), ctx.zone());
        // 대상 앱은 멤버 바인딩(오늘 0시 기준 세트)이 우선, 없으면 config.targetPackages 폴백.
        List<String> memberApps = ctx.memberScreenApps();
        Set<String> targets = (memberApps != null && !memberApps.isEmpty())
                ? new HashSet<>(memberApps)
                : (cfg.targetPackages() != null ? new HashSet<>(cfg.targetPackages()) : Set.of());

        // 그날 원본 전부를 패키지별 시간순으로 페어링한다(이월 상태 없음).
        Map<String, Instant> open = new HashMap<>();
        long accSec = processEvents(ctx.signals(), targets, window, open);

        // 창 닫힘 이후면 남은 open 세션을 창 끝에서 종료(꼬리 시간 반영)
        if (!ctx.now().isBefore(window.end())) {
            accSec += closeOpenAt(window.end(), window, open);
            open.clear();
        }

        long usageMin = accSec / 60;
        int goal = (cfg.goalMinutes() != null) ? cfg.goalMinutes() : 0;

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("usageMinutes", usageMin);
        evidence.put("goalMinutes", goal);
        evidence.put("mode", cfg.mode().name());
        evidence.put("usageSeconds", accSec);
        if (!open.isEmpty()) evidence.put("open", serializeOpen(open));   // 아직 안 닫힌 세션(설명용)

        if (cfg.mode() == ScreenTimeMode.MIN) {
            return (usageMin >= goal)
                    ? EvaluationOutcome.success(evidence, window.end())
                    : EvaluationOutcome.pending(evidence, window.end());
        } else { // MAX
            return (usageMin > goal)
                    ? EvaluationOutcome.violated("USAGE_EXCEEDED", evidence, window.end())
                    : EvaluationOutcome.pending(evidence, window.end());
        }
    }

    /** 사용 구간을 여는 이벤트. */
    private static final Set<String> OPEN_EVENTS = Set.of("RESUMED", "ACTIVITY_RESUMED", "MOVE_TO_FOREGROUND");

    /**
     * 사용 구간을 닫는 이벤트.
     *
     * <p>PAUSED 가 표준이지만 <b>빠질 때가 있다</b> — 앱이 강제 종료되거나 프로세스가 죽으면
     * UsageStats 에 STOPPED 만 남는다. 그때 닫지 않으면 그 세션이 창 끝까지 열려 있는 것으로
     * 계산돼, 최소 사용형은 안 쓴 시간을 인정받고 최대 사용형은 쓰지 않은 시간으로 실패한다.
     * 스펙이 「종료를 판단할 수 있는 후속 정보를 이용해 보정할 수 있음」이라고 둔 자리다.
     */
    private static final Set<String> CLOSE_EVENTS =
            Set.of("PAUSED", "ACTIVITY_PAUSED", "STOPPED", "ACTIVITY_STOPPED", "MOVE_TO_BACKGROUND");

    /** 화면이 꺼지면 어떤 앱도 쓰이고 있지 않다 — 열린 세션 전부를 닫는 시스템 이벤트다. */
    private static final Set<String> SCREEN_OFF_EVENTS =
            Set.of("SCREEN_OFF", "SCREEN_NON_INTERACTIVE", "DEVICE_SHUTDOWN");

    /** 그날 이벤트를 패키지별로 페어링해 닫힌 구간의 창 내 초를 반환. open 맵에는 미완 세션이 남는다. */
    private long processEvents(List<SyncSignal> signals, Set<String> targets, Window window, Map<String, Instant> open) {
        // 패키지별 (type, at) 수집
        Map<String, List<UsageEvent>> byPkg = new HashMap<>();
        List<Instant> screenOff = new ArrayList<>();
        if (signals != null) {
            for (SyncSignal s : signals) {
                if (!carriesUsage(s.type())) continue;
                collectScreenOff(s, screenOff);
                if (s.usageEvents() == null) continue;
                for (UsageEvent e : s.usageEvents()) {
                    if (e.packageName() == null) continue;
                    if (!targets.isEmpty() && !targets.contains(e.packageName())) continue;
                    byPkg.computeIfAbsent(e.packageName(), k -> new ArrayList<>()).add(e);
                }
            }
        }
        screenOff.sort(Comparator.naturalOrder());

        long addedSec = 0;
        for (var entry : byPkg.entrySet()) {
            String pkg = entry.getKey();
            List<UsageEvent> events = entry.getValue();
            events.sort(Comparator.comparing(ev -> safeInstant(ev.at())));
            int offIndex = 0;
            for (UsageEvent ev : events) {
                Instant at = safeInstant(ev.at());
                if (at == null) continue;

                // 이 이벤트 이전에 화면이 꺼졌다면 그 시점에 세션을 닫는다(보정).
                while (offIndex < screenOff.size() && !screenOff.get(offIndex).isAfter(at)) {
                    Instant offAt = screenOff.get(offIndex++);
                    Instant opened = open.remove(pkg);
                    if (opened != null) addedSec += overlapSeconds(opened, offAt, window);
                }

                String type = (ev.type() != null) ? ev.type().toUpperCase() : "";
                if (OPEN_EVENTS.contains(type)) {
                    open.putIfAbsent(pkg, at);                  // 이미 열려있으면 유지(가장 이른 것)
                } else if (CLOSE_EVENTS.contains(type)) {
                    Instant opened = open.remove(pkg);
                    if (opened != null) addedSec += overlapSeconds(opened, at, window);
                }
            }
            // 마지막 이벤트 뒤에 온 화면 꺼짐도 세션을 닫는다.
            while (offIndex < screenOff.size()) {
                Instant offAt = screenOff.get(offIndex++);
                Instant opened = open.remove(pkg);
                if (opened != null) addedSec += overlapSeconds(opened, offAt, window);
            }
        }
        return addedSec;
    }

    /** 앱 사용 이벤트를 실어 오는 입력 타입. */
    private boolean carriesUsage(String type) {
        return SignalType.SCREEN_TIME.name().equals(type) || SignalType.WAKE.name().equals(type);
    }

    private void collectScreenOff(SyncSignal s, List<Instant> out) {
        if (s.screenEvents() == null) return;
        for (var e : s.screenEvents()) {
            if (e == null || e.event() == null) continue;
            if (!SCREEN_OFF_EVENTS.contains(e.event().toUpperCase())) continue;
            Instant at = safeInstant(e.at());
            if (at != null) out.add(at);
        }
    }

    private long closeOpenAt(Instant closeAt, Window window, Map<String, Instant> open) {
        long sec = 0;
        for (Instant opened : open.values()) sec += overlapSeconds(opened, closeAt, window);
        return sec;
    }

    /** [start,end] ∩ window 의 초. */
    private long overlapSeconds(Instant start, Instant end, Window window) {
        if (start == null || end == null || !end.isAfter(start)) return 0;
        Instant s = start.isBefore(window.start()) ? window.start() : start;
        Instant e = end.isAfter(window.end()) ? window.end() : end;
        long sec = e.getEpochSecond() - s.getEpochSecond();
        return Math.max(sec, 0);
    }

    // ===== evidence 직렬화 =====
    private Map<String, String> serializeOpen(Map<String, Instant> open) {
        Map<String, String> out = new HashMap<>();
        for (var e : open.entrySet()) out.put(e.getKey(), e.getValue().toString());
        return out;
    }

    private Window resolveWindow(String timeWindow, LocalDate date, ZoneId zone) {
        if (timeWindow != null && timeWindow.contains("-")) {
            String[] p = timeWindow.split("-", 2);
            try {
                Instant s = ZonedDateTime.of(date, LocalTime.parse(p[0].trim()), zone).toInstant();
                Instant e = ZonedDateTime.of(date, LocalTime.parse(p[1].trim()), zone).toInstant();
                return new Window(s, e);
            } catch (Exception ignored) { /* fall through */ }
        }
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        return new Window(start, end);
    }

    private Instant safeInstant(String iso) { return TimeWindows.parseInstant(iso); }
}
