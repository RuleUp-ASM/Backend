package com.ruleup.ruleup_backend.verification.dto;

import java.math.BigDecimal;

/** §3.2 진행률 항목(챌린지별). 빈도형은 (완료/필요/남은)을 횟수로 해석. */
public record ChallengeProgress(
        String challengeId,
        String title,
        String category,
        String participationType,
        String status,
        String scheduleType,
        BigDecimal progressRate,
        int successDays,
        int targetDays,
        int remainingDays,
        boolean todayTarget,
        String todayStatus,
        Period period,          // 빈도형만, 아니면 null
        String lastSyncedAt
) {
    /** 빈도형 주기 정보. */
    public record Period(String unit, Integer target, Integer completed, Integer remaining, String endsAt) {}
}
