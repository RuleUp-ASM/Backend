package com.ruleup.ruleup_backend.me.dto;

import java.math.BigDecimal;
import java.util.List;

/** 평판 히스토리(GET /me/reputation/history): 역대 최고 온도 + 마일스톤 피드. */
public record MeReputationHistoryResponse(Peak peak, List<Milestone> milestones) {

    public record Peak(BigDecimal temperature, String achievedAt) {}

    public record Milestone(String type, String label, String achievedAt) {}
}
