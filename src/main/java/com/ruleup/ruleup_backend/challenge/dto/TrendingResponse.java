package com.ruleup.ruleup_backend.challenge.dto;

import java.util.List;

/**
 * 홈 "실시간 인기" 응답 — Trending API 명세.
 *
 * @param calculatedAt 순위 계산 기준 시각(최대 1시간 지연 — 2026-08-11 10분에서 변경)
 * @param items        Top 20. 후보가 적으면 있는 만큼, 초기엔 빈 배열
 */
public record TrendingResponse(String calculatedAt, List<Item> items) {

    /**
     * @param participantCount 응답 시점에 DB 에서 다시 읽은 현재 인원 — 공용 캐시에는 랭킹만 담는다
     * @param joinable         내 표시 티어로 들어갈 수 있는지. <b>필터가 아니라 잠금 아이콘용</b>이다 —
     *                         못 들어가는 방도 인기 목록에는 보이며 이는 의도된 동작(정책 §3.1)
     */
    public record Item(
            int rank,
            String challengeId,
            String title,
            String imageUrl,
            String category,
            Integer participantCount,
            Integer recentJoins24h,
            String verificationType,
            String minTier,
            boolean joinable,
            String endDate
    ) {}
}
