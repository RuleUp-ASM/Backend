package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ChallengeDetailResponse(
        String challengeId,
        String title,
        String description,
        String imageUrl,
        String category,
        String participationType,
        String status,
        String moderationStatus,
        String fixDeadline,
        String anonymity,
        Owner owner,
        List<String> repeatDays,
        Integer durationDays,
        String startDate,
        String endDate,
        Long templateId,
        VerificationConfig verification,
        Map<String, Object> params,
        PenaltyConfig penalty,
        RewardConfig reward,
        Stats stats,
        Eligibility eligibility,
        String myRole   // 요청자의 역할: OWNER / MEMBER / NONE
) {
    public record Owner(String nickname) {}

    public record Stats(
            Integer participantCount,
            BigDecimal averageMannerTemperature,
            BigDecimal completionRate
    ) {}

    public record Eligibility(
            boolean canJoin,
            BigDecimal myMannerTemperature,
            BigDecimal minMannerTemperature
    ) {}
}