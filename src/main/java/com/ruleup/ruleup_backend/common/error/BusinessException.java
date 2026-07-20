package com.ruleup.ruleup_backend.common.error;

import lombok.Getter;

/**
 * 서비스 로직에서 의도적으로 던지는 예외.
 * 예: throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
 * → GlobalExceptionHandler가 받아 통일된 형식으로 응답.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    /** 선택: 클라이언트 분기용 사유(예: OWNER_CANNOT_LEAVE → DELEGATE_FIRST/DELETE_INSTEAD). 없으면 null. */
    private final String detail;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = detail;
    }
}