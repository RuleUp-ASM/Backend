package com.ruleup.ruleup_backend.verification.service;

import java.time.DayOfWeek;

/** DayOfWeek → repeatDays 코드(MON..SUN) 매핑. challenge.repeatDays와 대조용. */
final class WeekdayCodes {
    private WeekdayCodes() {}
    static String code(DayOfWeek d) {
        return switch (d) {
            case MONDAY -> "MON";
            case TUESDAY -> "TUE";
            case WEDNESDAY -> "WED";
            case THURSDAY -> "THU";
            case FRIDAY -> "FRI";
            case SATURDAY -> "SAT";
            case SUNDAY -> "SUN";
        };
    }
}
