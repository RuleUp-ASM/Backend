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
        Integer maxParticipants,
        Stats stats,
        Eligibility eligibility,
        String myRole   // 요청자의 역할: OWNER / MANAGER / MEMBER / NONE
) {
    public record Owner(String nickname) {}

    public record Stats(
            Integer participantCount,
            BigDecimal averageMannerTemperature,
            BigDecimal completionRate
    ) {}

    /** canJoin은 정원·기준온도·재참여·모더레이션·상태를 종합한 참여 가능 여부. */
    public record Eligibility(
            boolean canJoin,
            BigDecimal myMannerTemperature,
            BigDecimal minMannerTemperature,
            boolean rejoinBlocked
    ) {}
}