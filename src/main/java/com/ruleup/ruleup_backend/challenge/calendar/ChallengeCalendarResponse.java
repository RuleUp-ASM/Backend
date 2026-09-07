package com.ruleup.ruleup_backend.challenge.calendar;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 챌린지 단위 월 캘린더(GET /challenges/{challengeId}/calendar).
 *
 * <p><b>{@code /me/calendar} 와 상태값이 다르다.</b> 계정 단위 캘린더는 하루에 걸린 여러 루틴을
 * 합산하므로 {@code ALL_DONE}·{@code PARTIAL} 이 있는데, 한 챌린지로 좁히면 하루 판정 대상이
 * 1건이라 그 값이 의미를 잃는다. 기존 API 에 {@code challengeId} 필터를 붙이지 않고 별도
 * 엔드포인트로 둔 이유가 이것이다 — 응답 스키마 자체가 다르다.
 */
@Schema(name = "ChallengeCalendarResponse", description = "챌린지 한 개의 월 단위 판정 결과")
public record ChallengeCalendarResponse(

        @Schema(description = "요청 챌린지") String challengeId,

        @Schema(description = "요청 월", example = "2026-09") String month,

        @Schema(description = "판정 대상일만. 비대상일은 배열에 없다") List<Day> days) {

    @Schema(name = "ChallengeCalendarDay")
    public record Day(

            @Schema(example = "2026-09-05") String date,

            @Schema(description = """
                    DONE(완료) / FAILED(실패) / FAIL_EXPECTED(실패 예정 — 유예 창) / IN_PROGRESS(오늘).

                    **`/me/calendar` 와 enum 이 다르다** — 하루 판정 대상이 1건이라
                    `ALL_DONE`·`PARTIAL` 이 없다.""",
                    example = "DONE",
                    allowableValues = {"DONE", "FAILED", "FAIL_EXPECTED", "IN_PROGRESS"})
            String status,

            @Schema(description = "해당일 인증 건. 미제출이거나 확정 이력만 남은 과거 건은 null")
            String verificationId,

            @Schema(description = "이의 신청 가능 여부 — 캘린더에서 바로 진입할 수 있는지")
            boolean appealable) {}
}
