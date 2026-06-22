package com.ruleup.ruleup_backend.verification.evaluator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** 신호 시각 파싱 + "HH:mm" / "HH:mm-HH:mm" → 해당 날짜의 KST Instant 변환 유틸. */
public final class TimeWindows {
    private TimeWindows() {}

    /** ISO-8601(오프셋/Z 포함) 문자열 → Instant. 파싱 실패 시 null. */
    public static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            try {
                return ZonedDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** 해당 날짜 자정(KST) Instant. */
    public static Instant startOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    /** 해당 날짜의 "HH:mm" 시각(KST) Instant. */
    public static Instant atTime(LocalDate date, String hhmm, ZoneId zone) {
        LocalTime t = LocalTime.parse(hhmm);
        return ZonedDateTime.of(date, t, zone).toInstant();
    }
}
