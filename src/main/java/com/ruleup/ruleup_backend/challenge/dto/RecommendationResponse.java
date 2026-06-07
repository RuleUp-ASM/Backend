package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;

import java.math.BigDecimal;
import java.util.List;

/**
 * 3.1 추천 응답(data). 상태 저장 없음 — 모든 값은 클라에서 수정 가능함
 * penalty/reward는 도메인 값 객체를 그대로 재사용(JSON 구조 동일).
 */
public record RecommendationResponse(
        String title,
        String description,
        String category,                 // SOLO/GROUP 아님 - InterestCategory 코드
        String participationType,        // SOLO / GROUP
        BigDecimal minMannerTemperature, // 그룹만, 솔로는 null
        List<String> repeatDays,         // 예 ["MON".."FRI"]
        Integer durationDays,
        String startDate,                // ISO date (서버 기준 오늘)
        String endDate,                  // ISO date (start + duration - 1)
        List<String> verificationMethods,
        PenaltyConfig penalty,
        RewardConfig reward
) {}