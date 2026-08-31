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
    /** 선택: SETTING_CHANGE_LIMIT 일 때의 다음 변경 가능 시각(ISO). 없으면 null. */
    private final String nextChangeAvailableAt;
    /** 선택: CONFIRMATION_REQUIRED 일 때의 확인 토큰. 없으면 null. */
    private final String confirmationToken;
    /** 선택: CONFIRMATION_REQUIRED 일 때 재확인 화면에 보여줄 요약. 없으면 null. */
    private final Object preview;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public BusinessException(ErrorCode errorCode, String detail, String rejoinAvailableAt) {
        this(errorCode, detail, rejoinAvailableAt, null);
    }

    private BusinessException(ErrorCode errorCode, String detail,
                              String rejoinAvailableAt, String nextChangeAvailableAt) {
        this(errorCode, detail, rejoinAvailableAt, nextChangeAvailableAt, null, null);
    }

    private BusinessException(ErrorCode errorCode, String detail, String rejoinAvailableAt,
                              String nextChangeAvailableAt, String confirmationToken, Object preview) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = detail;
        this.rejoinAvailableAt = rejoinAvailableAt;
        this.nextChangeAvailableAt = nextChangeAvailableAt;
        this.confirmationToken = confirmationToken;
        this.preview = preview;
    }

    /**
     * 2단계 확인 요구 — 무엇을 확인하는지(preview)와 그 확인에 한해 유효한 토큰을 함께 던진다.
     * 토큰 없이 실행을 시도하면 항상 여기서 멈추므로, 클라이언트 모달을 우회해도 집행되지 않는다.
     */
    public static BusinessException confirmationRequired(String confirmationToken, Object preview) {
        return new BusinessException(ErrorCode.CONFIRMATION_REQUIRED, null, null, null,
                confirmationToken, preview);
    }

    /**
     * 월 1회 변경 한도 소진 — 다음 변경 가능 시각을 응답 본문에 함께 실어 보낸다.
     * 클라는 이 값으로 "다음 달 1일부터 바꿀 수 있어요" 안내를 바로 띄운다(인증 구현 API 명세).
     */
    public static BusinessException settingChangeLimit(String nextChangeAvailableAt) {
        return new BusinessException(ErrorCode.SETTING_CHANGE_LIMIT, null, null, nextChangeAvailableAt);
    }
}