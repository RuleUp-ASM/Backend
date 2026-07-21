package com.ruleup.ruleup_backend.me.dto;

import java.math.BigDecimal;
import java.util.List;

/** 매너 온도 상세(GET /me/reputation). */
public record MeReputationResponse(
        BigDecimal current,
        String bandLabel,
        NextTier nextTier,
        List<Change> recentChanges
) {
    public record NextTier(BigDecimal target, BigDecimal progressRate, String label) {}

    public record Change(String date, BigDecimal temperature, BigDecimal delta, String label) {}
}
