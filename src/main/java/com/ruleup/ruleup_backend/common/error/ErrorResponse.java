package com.ruleup.ruleup_backend.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 에러 응답의 공통 본문 (테크 스펙 3.5).
 * 예: { "code": "NICKNAME_DUPLICATED", "message": "이미 사용 중인 닉네임입니다." }
 * reason: 클라이언트 분기용 선택 필드(예: OWNER_CANNOT_LEAVE → DELEGATE_FIRST/DELETE_INSTEAD). 없으면 직렬화 생략.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String reason) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String reason) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), reason);
    }
}
