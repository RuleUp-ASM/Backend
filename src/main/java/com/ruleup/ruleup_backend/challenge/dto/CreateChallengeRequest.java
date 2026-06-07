package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;

import java.math.BigDecimal;
import java.util.List;

/**
 * 3.2 생성 요청 — 추천을 수정·확정한 최종값.
 * endDate는 보내지 않는다(서버가 startDate + durationDays로 파생).
 */
public record CreateChallengeRequest(
        String title,
        String description,
        String imageUrl,                 // 선택, 미설정 시 null
        String category,
        String participationType,        // SOLO / GROUP
        BigDecimal minMannerTemperature, // 그룹 참여 기준 (솔로면 무시)
        List<String> repeatDays,
        Integer durationDays,
        String startDate,                // ISO date
        List<String> verificationMethods,// ≥1
        PenaltyConfig penalty,
        RewardConfig reward,
        String anonymity                 // REAL / ANONYMOUS
) {}