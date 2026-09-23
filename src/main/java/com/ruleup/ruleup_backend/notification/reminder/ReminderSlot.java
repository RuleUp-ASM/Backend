package com.ruleup.ruleup_backend.notification.reminder;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 리마인더 슬롯 — 08:00 · 12:00 · 19:00 KST.
 *
 * <p>{@code dedup_key = ROUTINE_REMINDER:{user_id}:{date}:{slot}} 의 마지막 조각이다.
 * <b>키에 슬롯이 있어 억제가 불필요하고</b>, 멀티 태스크 중복 실행도 이 UNIQUE 하나로 막힌다 —
 * ShedLock 이 필요 없는 이유다.
 */
public enum ReminderSlot {

    MORNING(8),
    NOON(12),
    EVENING(19);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final int hour;

    ReminderSlot(int hour) {
        this.hour = hour;
    }

    public int hour() {
        return hour;
    }

    /**
     * 지금이 어느 슬롯인가. 크론이 정각에 깨우지만 지연·재시도가 있을 수 있어
     * <b>가장 최근에 지난 슬롯</b>으로 해석한다 — 08:03 에 돌아도 MORNING 이다.
     */
    public static ReminderSlot at(Instant now) {
        LocalTime time = ZonedDateTime.ofInstant(now, KST).toLocalTime();
        if (time.getHour() >= EVENING.hour) return EVENING;
        if (time.getHour() >= NOON.hour) return NOON;
        return MORNING;
    }
}
