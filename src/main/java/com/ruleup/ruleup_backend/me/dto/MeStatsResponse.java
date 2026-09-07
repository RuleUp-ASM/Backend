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
@Schema(name = "MeStatsResponse", description = """
        통계 4종. 확정된 판정만 센다 — 유예 중인 최근 2일치는 아직 반영되지 않는다.
        successRate 만 null 이 될 수 있다(판정 이력 0건).""")
public record MeStatsResponse(

        @Schema(description = """
                ① 전체 성공률 — 성공 ÷ (성공+실패). 방 랭킹과 동일 산식.

                **판정 이력이 하나도 없으면 null 이다.** 「표본이 없다」와 「0%다」는 다른 사실이고,
                0.0 을 내리면 가입 직후 통계 화면이 아무것도 하지 않은 사용자에게 전부 실패했다고
                말하게 된다. 클라이언트는 null 을 「기록 없음」으로 그린다.

                나머지 4종은 0 이 유효한 값이므로 그대로 0 을 내린다 — 성공 0건은 실제로 0건이다.""",
                example = "0.87", nullable = true)
        Double successRate,

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
