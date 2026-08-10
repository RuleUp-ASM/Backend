package com.ruleup.ruleup_backend.challenge.dto;

import java.util.List;

/**
 * 목록 조회 응답 — explore API 명세.
 *
 * <p><b>전체 개수를 주지 않는다.</b> 무한 스크롤 UI 라 불필요하고, 매 요청 COUNT 쿼리가
 * 데이터가 늘수록 p95 1초 목표에 부담이 되기 때문이다(계약에서 제거).
 */
public record ExploreResponse(List<Item> items, String nextCursor, boolean hasNext) {

    /**
     * @param startsSoon     시작 전 방인가 — true 면 진행 지표는 전부 null
     * @param isFull         정원 마감. <b>true 여도 목록에는 남는다</b> — 탈퇴로 자리가 나거나
     *                       정원이 늘 수 있어 숨기지 않고 뱃지로만 구분한다(정책 §4.1)
     * @param eligible       내 표시 티어로 들어갈 수 있는지
     * @param completionRate 완주율 0~1. 표본 미달이면 null(화면 미표시)
     * @param retentionRate  유지율 0~1. 표본 미달이면 null
     */
    public record Item(
            String challengeId,
            String title,
            String imageUrl,
            String category,
            String verificationType,
            boolean startsSoon,
            int participantCount,
            Integer capacity,
            boolean isFull,
            String minTier,
            boolean eligible,
            Double completionRate,
            Double retentionRate,
            Integer dday,
            String startDate,
            String endDate,
            String createdAt
    ) {}
}
