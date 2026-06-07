package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;

import java.math.BigDecimal;
import java.util.List;

/**
 * 3.4 수정 요청 — 변경 필드만(전부 선택, null이면 변경 안 함).
 * durationDays/startDate 변경 시 endDate 재파생.
 */
public record UpdateChallengeRequest(
        String title,
        String description,
        String category,
        List<String> repeatDays,
        Integer durationDays,
        String startDate,
        List<String> verificationMethods,
        PenaltyConfig penalty,
        RewardConfig reward,
        BigDecimal minMannerTemperature
) {}