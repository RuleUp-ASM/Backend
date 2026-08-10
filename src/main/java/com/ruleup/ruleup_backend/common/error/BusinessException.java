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
    /** 선택: 클라이언트 분기용 사유(예: JOIN_BLOCKED → PRIVATE_INVITE_ONLY/FULL/TIER_GATE). 없으면 null. */
    private final String detail;
    /** 선택: JOIN_BLOCKED + REJOIN_COOLDOWN 일 때의 재입장 가능 시각(ISO). 없으면 null. */
    private final String rejoinAvailableAt;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public BusinessException(ErrorCode errorCode, String detail, String rejoinAvailableAt) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = detail;
        this.rejoinAvailableAt = rejoinAvailableAt;
    }
}