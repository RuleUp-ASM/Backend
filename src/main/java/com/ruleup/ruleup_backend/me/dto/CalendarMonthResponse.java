package com.ruleup.ruleup_backend.me.dto;

import java.util.List;

/** 월 단위 활동 캘린더(GET /me/calendar). 판정 대상일만 내려간다. */
public record CalendarMonthResponse(String month, List<Day> days) {

    /** status: ALL_DONE / PARTIAL / FAILED / PENDING / NOT_TARGET. */
    public record Day(String date, String status, int successCount, int targetCount) {}
}
