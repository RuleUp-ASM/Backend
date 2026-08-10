package com.ruleup.ruleup_backend.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 에러 응답의 공통 본문 (테크 스펙 3.5).
 * 예: { "code": "NICKNAME_DUPLICATED", "message": "이미 사용 중인 닉네임입니다." }
 * reason: 클라이언트 분기용 선택 필드(예: JOIN_BLOCKED → PRIVATE_INVITE_ONLY/FULL/TIER_GATE). 없으면 직렬화 생략.
 * rejoinAvailableAt: REJOIN_COOLDOWN 일 때만 함께 실린다. 그 외엔 생략.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String reason, String rejoinAvailableAt) {

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
