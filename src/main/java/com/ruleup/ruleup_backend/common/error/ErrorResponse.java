package com.ruleup.ruleup_backend.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 에러 응답의 공통 본문 (테크 스펙 3.5).
 * 예: { "code": "NICKNAME_DUPLICATED", "message": "이미 사용 중인 닉네임입니다." }
 * reason: 클라이언트 분기용 선택 필드(예: JOIN_BLOCKED → PRIVATE_INVITE_ONLY/FULL/TIER_GATE). 없으면 직렬화 생략.
 * rejoinAvailableAt: REJOIN_COOLDOWN 일 때만 함께 실린다. 그 외엔 생략.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@io.swagger.v3.oas.annotations.media.Schema(description = "에러 상세. 분기는 code 로 하고, message 는 사용자에게 그대로 보여줄 수 있다.")
public record ErrorResponse(

        @io.swagger.v3.oas.annotations.media.Schema(description = "에러 코드 — 클라이언트 분기 키", example = "NICKNAME_DUPLICATED")
        String code,

        @io.swagger.v3.oas.annotations.media.Schema(description = "사용자 안내 문구", example = "이미 사용 중인 닉네임입니다.")
        String message,

        @io.swagger.v3.oas.annotations.media.Schema(description = "세부 사유 — 해당 코드에만 실린다. 없으면 필드가 생략된다.")
        String reason,

        @io.swagger.v3.oas.annotations.media.Schema(description = "재참여 가능 시각 — JOIN_BLOCKED + REJOIN_COOLDOWN 일 때만 실린다.")
        String rejoinAvailableAt) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), null, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String reason) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), reason, null);
    }

    /** JOIN_BLOCKED + REJOIN_COOLDOWN 전용 — 재입장 가능 시각을 함께 내려준다(가입 API 명세). */
    public static ErrorResponse of(ErrorCode errorCode, String reason, String rejoinAvailableAt) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), reason, rejoinAvailableAt);
    }
}
