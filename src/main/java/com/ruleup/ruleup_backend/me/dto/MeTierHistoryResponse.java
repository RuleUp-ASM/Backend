package com.ruleup.ruleup_backend.me.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 티어 히스토리(GET /me/tier/history) — 구 {@code /me/reputation/history} 대체.
 *
 * <p>정책이 <b>그래프 형식 + 하락 사유 표기 없음</b>으로 못박았기 때문에 스냅샷 시리즈와 역대 최고만
 * 내린다. 구 마일스톤·피크 피드는 정책 근거가 없어 폐기됐다. 보관은 1년이고 그 이전 이력은
 * 조회되지 않는다.
 */
@Schema(name = "MeTierHistoryResponse", description = "월말 스냅샷 그래프 원천. 하락 사유는 표기하지 않는다.")
public record MeTierHistoryResponse(

        @Schema(description = "역대 최고(보관 1년 범위 안). 이력이 없으면 null")
        Best best,

        @Schema(description = "월말 스냅샷(오래된 달 → 최근 달)")
        List<Monthly> monthly,

        @Schema(description = "보관 기간 안내 문구", example = "1년 보관")
        String retentionNote) {

    @Schema(name = "MeTierHistoryBest")
    public record Best(
            @Schema(example = "GOLD") String tier,
            @Schema(example = "350") long score,
            @Schema(description = "달성일 (KST)", example = "2026-05-10") String date) {}

    @Schema(name = "MeTierHistoryMonthly")
    public record Monthly(
            @Schema(example = "2026-06") String month,
            @Schema(example = "GOLD") String endTier,
            @Schema(example = "320") long endScore) {}
}
