package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;

import java.math.BigDecimal;
import java.util.List;

/**
 * 3.2 생성 / 3.4 수정 공통 응답(data). 통계·참여자격이 없는 챌린지 본문.
 */
public record ChallengeResponse(
        String challengeId,
        String status,
        String title,
        String description,
        String imageUrl,
        String category,
        String participationType,
        BigDecimal minMannerTemperature,
        List<String> repeatDays,
        Integer durationDays,
        String startDate,
        String endDate,
        List<String> verificationMethods,
        PenaltyConfig penalty,
        RewardConfig reward
) {
    public static ChallengeResponse from(Challenge c) {
        return new ChallengeResponse(
                c.getId().toString(),
                c.getStatus().name(),
                c.getTitle(),
                c.getDescription(),
                c.getImageUrl(),
                c.getCategory(),
                c.getParticipationType().name(),
                c.getMinMannerTemperature(),
                c.getRepeatDays(),
                c.getDurationDays(),
                c.getStartDate().toString(),
                c.getEndDate().toString(),
                c.getVerificationMethods(),
                c.getPenalty(),
                c.getReward());
    }
}