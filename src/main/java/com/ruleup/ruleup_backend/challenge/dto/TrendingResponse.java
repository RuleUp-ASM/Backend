package com.ruleup.ruleup_backend.challenge.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 홈 "실시간 인기"(탐색 §2.1) 응답. 10분 배치가 계산한 랭킹을 캐시에서 서빙(최대 10분 지연).
 *  - calculatedAt : 랭킹 계산 기준 시각(ISO-8601).
 *  - items        : 인기 Top 20(후보가 20 미만이면 있는 만큼, 없으면 []).
 */
public record TrendingResponse(
        String calculatedAt,
        List<Item> items
) {
    public record Item(
            int rank,
            String challengeId,
            String title,
            String category,
            Integer participantCount,
            Integer recentJoins24h,
            String participationType,
            String verificationType,
            BigDecimal minMannerTemperature,
            String endDate
    ) {}
}
