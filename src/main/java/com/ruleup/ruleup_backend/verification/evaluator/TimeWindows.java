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

    /**
     * 신호 시각 → Instant. 파싱 실패 시 null.
     *
     * <p>ISO-8601(오프셋/Z 포함)과 <b>epoch millis</b> 를 모두 받는다. Android 전송 스펙은 모든 시각을
     * epoch millis 숫자로 보내고(설계 원칙 ①), 문자열 필드에 들어온 숫자는 Jackson 이 {@code "1789708111454"}
     * 로 바꿔 둔다. ISO 만 읽으면 실기기 신호의 시각이 전부 null 이 돼 판정에서 빠진다(QA SIG-19).
     */
    public static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        if (isEpochMillis(iso)) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(iso));
            } catch (NumberFormatException e) {
                return null;
            }
        }
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

    private static boolean isEpochMillis(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
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
