package com.ruleup.ruleup_backend.me.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 일자 상세(GET /me/calendar/{date}) — 그날의 루틴별 상태.
 *
 * <p>실패 건은 여기서 바로 이의 신청으로 들어가야 하므로 <b>이의 가능 여부를 같은 응답에 싣는다.</b>
 * 별도 API 로 빼면 일자 상세마다 N+1 호출이 되기 때문이다(6. 이외 고려 사항).
 */
@Schema(name = "CalendarDayResponse", description = "그날의 챌린지별 판정 결과와 이의 진입 가능 여부.")
public record CalendarDayResponse(
        @Schema(example = "2026-07-20") String date,
        List<Item> items) {

    @Schema(name = "CalendarDayItem")
    public record Item(
            String challengeId,
            @Schema(description = "챌린지 제목. 삭제된 방이면 null") String title,
            String category,
            @Schema(description = "인증 건 ID — 이의 신청 대상. 확정 이력만 남은 과거 건은 null")
            String verificationId,
            @Schema(description = "IN_PROGRESS / FAIL_EXPECTED / DONE / FAILED / NOT_TARGET",
                    example = "FAILED") String status,
            @Schema(description = "AUTO / MANUAL / MANUAL_FALLBACK") String verifiedVia,
            @Schema(description = "확정 시각") String confirmedAt,
            @Schema(description = "실패 사유 코드") String failureReason,
            @Schema(description = "실패·실패 예정 건에만 붙는다. 그 외에는 null") Appeal appeal) {}

    /**
     * 이의 진입 가능 여부.
     *
     * <p>구 계약의 {@code remainingThisMonth}·{@code LIMIT_EXCEEDED} 는 없다 —
     * 이의에 횟수 한도가 없어져 잔여 구제권이라는 개념 자체가 사라졌다(챌린지 정책 §7.2).
     */
    @Schema(name = "CalendarDayAppeal")
    public record Appeal(
            @Schema(description = "지금 이의를 신청할 수 있는지", example = "true") boolean eligible,
            @Schema(description = "WINDOW_CLOSED / ALREADY_APPEALED / null", example = "WINDOW_CLOSED")
            String ineligibleReason,
            @Schema(description = "신청 기한 = 귀속일+2일 00:00 KST (= 최종 확정 시각)")
            String eligibleUntil) {

        public static Appeal open(String until) { return new Appeal(true, null, until); }

        public static Appeal closed(String reason, String until) { return new Appeal(false, reason, until); }
    }
}
