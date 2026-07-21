package com.ruleup.ruleup_backend.me.dto;

import java.math.BigDecimal;
import java.util.List;

/** 기간별 통계(GET /me/stats). */
public record MeStatsResponse(
        String period,
        int totalCompleted,
        int avgCompletionRate,
        BigDecimal mannerDelta,
        BigDecimal avgStreak,
        List<Series> series,
        String insight
) {
    public record Series(String bucket, int completionRate) {}
}
