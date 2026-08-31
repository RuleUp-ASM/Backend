package com.ruleup.ruleup_backend.me.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 내 티어 상세(GET /me/tier) — 구 {@code /me/reputation}(매너 온도)을 전면 대체한다.
 *
 * <p>핵심은 <b>실제 티어와 표시 티어의 분리</b>다. 점수가 시작점보다 조금 낮아졌다고 표시 티어가
 * 곧바로 내려가면 방 게이팅까지 함께 흔들리므로, 강등에는 20점의 유예가 있다.
 */
@Schema(name = "MeTierResponse", description = """
        내 티어 상세. tier·score 는 실제 값이고 displayTier 는 유예를 반영한 표시값이다 —
        방 입장 자격도 displayTier 로 본다.""")
public record MeTierResponse(

        @Schema(description = "실제 티어 — 점수만으로 정해진다", example = "GOLD")
        String tier,

        @Schema(description = """
                누적 점수 0~2,000. 티어마다 0~99 로 끊지 않는 계정당 단일 축이라
                승급해도 초과 점수가 사라지지 않는다(가입 시 10점).""", example = "370")
        long score,

        @Schema(description = "표시 티어 — 강등 유예를 반영한 값", example = "GOLD")
        String displayTier,

        @Schema(description = "유예 구간 여부 — 점수는 이미 표시 티어 시작점 아래인데 아직 강등되지 않은 상태",
                example = "false")
        boolean graceBand,

        @Schema(description = "승급 안내. 루비면 null")
        Promotion promotion,

        @Schema(description = "강등 안내. 브론즈면 null")
        Demotion demotion,

        @Schema(description = "최근 변동 10건(최신순)")
        List<Change> recentChanges) {

    @Schema(name = "MeTierPromotion")
    public record Promotion(
            @Schema(description = "다음 티어", example = "DIAMOND") String nextTier,
            @Schema(description = "다음 티어 시작점까지 남은 점수", example = "130") long pointsToPromote) {}

    @Schema(name = "MeTierDemotion")
    public record Demotion(
            @Schema(description = "유예 하한 — 표시 티어 시작점 −20. 여기까지는 강등되지 않는다", example = "280")
            long graceFloor,
            @Schema(description = "이 점수 이하면 강등이 확정된다 — 시작점 −21", example = "279")
            long demoteAt) {}

    @Schema(name = "MeTierChange")
    public record Change(
            @Schema(description = "변동일 (KST)", example = "2026-07-21") String date,
            @Schema(description = """
                    CYCLE_SUCCESS / CYCLE_FAIL / LEAVE / KICK_FAIL / KICK_PERMISSION /
                    CHEAT / APPEAL_RESTORE""", example = "CYCLE_SUCCESS") String reason,
            @Schema(description = "변동을 일으킨 챌린지. 계정 단위 변동이면 null") String challengeId,
            @Schema(description = "변동량(양수=증가)", example = "5") long delta) {}
}
