package com.ruleup.ruleup_backend.challenge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 챌린지 탈퇴 응답 — 탈퇴 API 명세 200 OK.
 *
 * <p>구 명세의 "탈퇴 시 재참여 영구 불가"·"방장은 탈퇴 불가"는 둘 다 폐기됐다.
 *
 * @param scoreDelta        감점(0 = 면제·면책 적용). ⚠️ 수치 미확정 — 실제 점수 반영은 티어 모듈 소관이고
 *                          본 API 는 계약값 반환 + 트리거 로깅까지만 한다(테크스펙 Non-Goals)
 * @param exemptReason      {@code LONG_SUCCESS}(1년 이상 성공) / {@code SUCCESSION_GRACE}(승계 3일 면책) / null
 * @param botOwnerActivated 내가 방장이었고 봇방장 체제로 전환됐으면 true
 */
@Schema(description = "탈퇴 결과")
public record LeaveResponse(
        @Schema(example = "true") boolean left,

        @Schema(description = "중도 이탈 감점. 면제되면 0.", example = "-15") int scoreDelta,

        @Schema(description = "감점 면제 사유. 없으면 null.", example = "LONG_SUCCESS",
                allowableValues = {"LONG_SUCCESS", "SUCCESSION_GRACE"})
        String exemptReason,

        @Schema(description = "이 방에 다시 들어올 수 있는 시각(자진 탈퇴는 1주 고정)", example = "2026-08-24T10:00:00Z")
        String rejoinAvailableAt,

        @Schema(description = "내가 방장이었고 넘기지 않고 나가 봇방장 체제로 전환됐는지. "
                + "true 면 남은 멤버에게 승계 알림이 나간다.", example = "false")
        boolean botOwnerActivated
) {
    public static final String EXEMPT_LONG_SUCCESS = "LONG_SUCCESS";
    public static final String EXEMPT_SUCCESSION_GRACE = "SUCCESSION_GRACE";
}
