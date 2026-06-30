package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ChallengeResponse(
        String challengeId,
        String status,
        String moderationStatus,
        String title,
        String description,
        String imageUrl,
        String category,
        String participationType,
        String anonymity,
        BigDecimal minMannerTemperature,
        List<String> repeatDays,
        Integer durationDays,
        String startDate,
        String endDate,
        Long templateId,
        VerificationConfig verification,
        Map<String, Object> params,
        PenaltyConfig penalty,
        RewardConfig reward
) {
    public static ChallengeResponse from(Challenge c) {
        return new ChallengeResponse(
                c.getId().toString(), c.getStatus().name(), c.getModerationStatus().name(),
                c.getTitle(), c.getDescription(),
                c.getImageUrl(), c.getCategory(), c.getParticipationType().name(),
                c.getAnonymity().name(),
                c.getMinMannerTemperature(), c.getRepeatDays(), c.getDurationDays(),
                c.getStartDate().toString(), c.getEndDate().toString(),
                c.getTemplateId(), c.getVerificationConfig(), c.getParams(),
                c.getPenalty(), c.getReward());
    }
}