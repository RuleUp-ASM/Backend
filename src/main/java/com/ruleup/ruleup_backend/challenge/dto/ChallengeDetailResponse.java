package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;

import java.math.BigDecimal;
import java.util.List;

/**
 * 3.3 상세 응답(data). 설정 + 생성자 + 통계(표시용) + 참여 자격.
 */
public record ChallengeDetailResponse(
        String challengeId,
        String title,
        String description,
        String imageUrl,
        String category,
        String participationType,
        String status,
        Owner owner,
        List<String> repeatDays,
        Integer durationDays,
        String startDate,
        String endDate,
        List<String> verificationMethods,
        PenaltyConfig penalty,
        RewardConfig reward,
        Stats stats,
        Eligibility eligibility
) {
    /** 생성자 정보. 익명 챌린지(CH-10)면 nickname 마스킹. */
    public record Owner(String nickname) {}

    /** 통계(현재 상태로 계산되는 것만, 스펙 2.7). completionRate는 인증 기능 전까지 항상 null. */
    public record Stats(
            Integer participantCount,
            BigDecimal averageMannerTemperature, // ACTIVE 멤버 평균, 없으면 null
            BigDecimal completionRate            // 항상 null
    ) {}

    /** 참여 자격(CH-04). 서버 검증값. */
    public record Eligibility(
            boolean canJoin,
            BigDecimal myMannerTemperature,
            BigDecimal minMannerTemperature      // 그룹만, 솔로는 null
    ) {}
}