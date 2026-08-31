package com.ruleup.ruleup_backend.me.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 통계 리포트(GET /me/stats) — <b>정책 지표 4종 고정</b>이라 기간 파라미터가 없다.
 *
 * <p>구 WEEKLY/MONTHLY/YEARLY 는 폐기됐고, 최근 12주 사이클 성과도 정책 §3 에서 삭제됐다
 * (오픈 이슈 #7, 2026-08-31). 매너 온도 변화·평균 연속일·완주율 시리즈·인사이트는 구 체계 산물이다.
 * {@code weeklyScoreDelta} 는 점수 한도가 <b>챌린지별 사이클 순변동</b>으로 확정되면서
 * '계정 주간'이라는 단위 자체가 사라져 함께 폐기됐다.
 */
@Schema(name = "MeStatsResponse", description = "통계 4종. 확정된 판정만 센다 — 유예 중인 최근 2일치는 아직 반영되지 않는다.")
public record MeStatsResponse(

        @Schema(description = "① 전체 성공률 — 성공 ÷ (성공+실패). 방 랭킹과 동일 산식", example = "0.87")
        double successRate,

        @Schema(description = "② 총 성공 인증 수", example = "142")
        long totalSuccessCount,

        @Schema(description = "③ 스트릭")
        Streak streak,

        @Schema(description = "⑤ 완주 개수 — 완주 = 기간 중 80% 이상 성공", example = "24")
        long completedCount) {

    /**
     * 현재·최고 스트릭. 그날 예정된 판정을 <b>전부</b> 성공해야 이어지고 하나라도 실패하면 리셋된다.
     * 판정이 없는 날은 끊지 않는다 — 주 3회 루틴의 쉬는 날에 스트릭이 죽으면 안 되기 때문이다.
     */
    @Schema(name = "MeStatsStreak")
    public record Streak(
            @Schema(example = "6") int current,
            @Schema(example = "21") int best) {}
}
