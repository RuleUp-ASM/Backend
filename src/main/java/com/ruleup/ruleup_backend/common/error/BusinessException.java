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

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}