package com.ruleup.ruleup_backend.me.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 통계 리포트(GET /me/stats) — <b>정책 지표 5종 고정</b>이라 기간 파라미터가 없다.
 *
 * <p>구 WEEKLY/MONTHLY/YEARLY 는 폐기됐다. 매너 온도 변화·평균 연속일·완주율 시리즈·인사이트는
 * 구 체계 산물이다.
 *
 * <p><b>{@code cycles12w} 와 {@code weeklyScoreDelta} 를 되살린다</b>(2026-09-07). 전자는 정책이
 * 정한 지표 5종 중 ④번이라 범위에서 버릴 수 없고, 12주 사이클 그리드가 이 값으로 그려진다.
 * 후자는 <b>계정 단위 주간 합계로 재정의</b>했다 — 구 설명의 「사이클분 상한 ±15」는 폐기다.
 * 점수 및 티어 정책 §4.7 의 ±20 은 <b>챌린지별 각 사이클</b> 한도이지 계정 주간 한도가 아니며,
 * 정책이 「계정 합산 한도는 두지 않는다」고 명시했다. 무료 동시 참여 3개 기준으로 이번 주 변동은
 * ±60까지 나올 수 있으므로 <b>화면에 「최대 ±20」 같은 한도 문구를 붙이면 안 된다.</b>
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

        @Schema(description = """
                ④ 최근 12주 사이클 성과. **언제나 12칸**이고 오래된 주가 앞이다.
                판정이 없던 주도 NONE 으로 채워 내린다 — 빈 배열을 내리면 그리드를 그리는 쪽이
                ISO 주차를 직접 계산해 칸을 만들어야 한다.""")
        List<Cycle> cycles12w,

        @Schema(description = "⑤ 완주 개수 — 완주 = 기간 중 80% 이상 성공", example = "24")
        long completedCount,

        @Schema(description = """
                이번 주 점수 변동 — **계정 단위 주간 합계이며 한도가 없다.**
                정책 §4.7 의 ±20 은 챌린지별 각 사이클 한도지 계정 주간 한도가 아니다.
                동시 참여 3개면 ±60까지 나올 수 있으므로 화면에 한도 문구를 붙이지 않는다.""",
                example = "5")
        long weeklyScoreDelta) {

    /**
     * 한 주의 사이클 성과. 그 주에 확정된 판정을 <b>전부</b> 성공했으면 SUCCESS, 하나라도
     * 실패가 섞였으면 PARTIAL, 전부 실패면 FAIL, 판정 자체가 없었으면 NONE 이다.
     *
     * @param week   ISO-8601 주차 — {@code 2026-W28}
     * @param result SUCCESS / PARTIAL / FAIL / NONE
     */
    @Schema(name = "MeStatsCycle")
    public record Cycle(
            @Schema(example = "2026-W28") String week,
            @Schema(example = "SUCCESS", allowableValues = {"SUCCESS", "PARTIAL", "FAIL", "NONE"})
            String result) {}

    /**
     * 현재·최고 스트릭. 그날 예정된 판정을 <b>전부</b> 성공해야 이어지고 하나라도 실패하면 리셋된다.
     * 판정이 없는 날은 끊지 않는다 — 주 3회 루틴의 쉬는 날에 스트릭이 죽으면 안 되기 때문이다.
     */
    @Schema(name = "MeStatsStreak")
    public record Streak(
            @Schema(example = "6") int current,
            @Schema(example = "21") int best) {}
}
