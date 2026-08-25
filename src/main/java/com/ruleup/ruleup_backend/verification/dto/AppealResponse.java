package com.ruleup.ruleup_backend.verification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 인증 이의 접수 결과. 접수되면 곧 인용이라 결과값은 {@code ACCEPTED} 하나뿐이다 —
 * 기각 상태가 존재하지 않는다(형식 미달은 접수 자체가 되지 않고 400/409 로 끝난다).
 *
 * @param appealId 이의 ID
 * @param result   {@code ACCEPTED} 고정
 * @param restored 소급 정정 결과
 */
@Schema(name = "AppealResponse", description = "인증 이의 접수 결과(= 인용 결과)")
public record AppealResponse(
        @Schema(description = "이의 ID", example = "0192cccc-1111-7000-aaaa-222233334444")
        String appealId,

        @Schema(description = "ACCEPTED 고정 — 기각 상태 없음", example = "ACCEPTED")
        String result,

        @Schema(description = "소급 정정 결과")
        Restored restored) {

    public static final String ACCEPTED = "ACCEPTED";

    /**
     * @param verification 정정된 인증 상태 — {@code DONE} 고정
     * @param streak       정정 반영 후 연속 성공 일수
     * @param scoreDelta   정상 성공과 동일한 점수 증분
     */
    @Schema(name = "AppealRestored", description = "이의 인용에 따른 소급 정정 결과")
    public record Restored(
            @Schema(description = "정정된 인증 상태(DONE 고정)", example = "DONE") String verification,
            @Schema(description = "정정 반영 후 연속 성공 일수", example = "7") int streak,
            @Schema(description = "정상 성공과 동일한 점수 증분", example = "0") int scoreDelta) {}
}
