package com.ruleup.ruleup_backend.me.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name="MeStatsResponse",description="통계 4종. 성공은 즉시, 실패는 확정된 판정만 집계한다.")
public record MeStatsResponse(
        @Schema(description="성공 / (성공+확정 실패). 판정이 없으면 null") Double successRate,
        @Schema(description="총 성공 인증 수. 수동 인증 포함") long totalSuccessCount,
        Streak streak,
        @Schema(description="종료된 챌린지 중 기간 목표의 80% 이상 성공한 개수") long completedCount) {
    public record Streak(int current,int best) {}
}
