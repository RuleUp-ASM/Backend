package com.ruleup.ruleup_backend.challenge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 챌린지 탈퇴 응답 — 탈퇴 API 명세 200 OK.
 *
 * <p>구 명세의 "탈퇴 시 재참여 영구 불가"·"방장은 탈퇴 불가"는 둘 다 폐기됐다.
 *
 * @param scoreDelta 확정 중도 이탈 감점. AUTO 방·시작 후에만 적용하며 동일 트랜잭션 outbox로 전달한다.
 * @param exemptReason {@code LONG_SUCCESS}(1년 이상 성공) / null
 * @param botOwnerActivated 내가 방장이었고 봇방장 체제로 전환됐으면 true
 */
@Schema(description = "탈퇴 결과")
public record LeaveResponse(
        @Schema(example = "true") boolean left,

        @Schema(description = "중도 이탈 감점. 면제되거나 챌린지 시작 전이면 0.", example = "-15") int scoreDelta,

        @Schema(description = "감점 면제 사유. 없으면 null.", example = "LONG_SUCCESS",
                allowableValues = {"LONG_SUCCESS"})
        String exemptReason,

        @Schema(description = "이 방에 다시 들어올 수 있는 시각(자진 탈퇴는 1주 고정)", example = "2026-08-24T10:00:00Z")
        String rejoinAvailableAt,

        @Schema(description = "내가 방장이었고 넘기지 않고 나가 봇방장 체제로 전환됐는지. "
                + "봇방장 체제에서는 자동으로 운영한다.", example = "false")
        boolean botOwnerActivated
) {
    public static final String EXEMPT_LONG_SUCCESS = "LONG_SUCCESS";
    public static final String EXEMPT_SUCCESSION_GRACE = "SUCCESSION_GRACE";
}
