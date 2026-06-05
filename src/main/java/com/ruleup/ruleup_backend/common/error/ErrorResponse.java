package com.ruleup.ruleup_backend.common.error;

/**
 * 모든 에러 응답의 공통 본문 (테크 스펙 3.5).
 * 예: { "code": "NICKNAME_DUPLICATED", "message": "이미 사용 중인 닉네임입니다." }
 */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }
}