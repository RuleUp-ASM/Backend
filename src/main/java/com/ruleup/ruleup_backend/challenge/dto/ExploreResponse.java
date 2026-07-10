package com.ruleup.ruleup_backend.challenge.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 챌린지 둘러보기(탐색 §3) 응답. 필터 적용 후 전체 개수 + 커서 페이지.
 *  - totalCount : 필터 적용 후 전체 개수("전체 N 개" 표시용).
 *  - challenges : 목록(없으면 []).
 *  - nextCursor : 다음 페이지 커서(마지막 페이지면 null).
 *  - hasNext    : 다음 페이지 존재 여부.
 */
public record ExploreResponse(
        Integer totalCount,
        List<Item> challenges,
        String nextCursor,
        Boolean hasNext
) {
    public record Item(
            String challengeId,
            String templateId,
            String title,
            String imageUrl,
            String category,
            String participationType,
            String verificationType,
            String status,
            String anonymity,
            Integer participantCount,
            BigDecimal minMannerTemperature,
            Boolean joinable,
            Integer templateUsageCount,
            Double completionRate,           // 템플릿 완주율(0~1). 표본 부족 시 null
            SuccessFailRatio successFailRatio,// 방 성공/실패. 표본·진행 부족 시 null
            List<String> repeatDays,
            Integer durationDays,
            String startDate,
            String endDate,
            String createdAt
    ) {}

    /** 방 단위 성공/실패(§3.2.4). */
    public record SuccessFailRatio(Integer successCount, Integer failCount, Double successRate) {}
}
