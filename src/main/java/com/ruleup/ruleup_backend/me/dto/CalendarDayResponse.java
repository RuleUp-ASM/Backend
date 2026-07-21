package com.ruleup.ruleup_backend.me.dto;

import java.util.List;

/** 일자 상세(GET /me/calendar/{date}): 챌린지별 결과. */
public record CalendarDayResponse(String date, List<Item> items) {

    public record Item(
            String challengeId,
            String title,
            String category,
            String status,          // SUCCESS / FAILED / PENDING / NOT_REQUIRED
            String verifiedVia,     // AUTO / MANUAL / MANUAL_FALLBACK (nullable)
            String verifiedAt,      // nullable
            String failureReason    // nullable
    ) {}
}
