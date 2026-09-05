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
    /**
     * 선택: 이 상황에 맞게 채워 넣은 사용자 문구. 없으면 {@link ErrorCode#getMessage()} 를 그대로 쓴다.
     *
     * <p>ErrorCode 의 문구는 상수라 "다른 계정" 같은 뭉뚱그린 표현밖에 못 담는다. 그런데 서버는
     * 그게 <b>카카오</b>인지 구글인지 이미 알고 있다 — 아는 걸 문구에 넣어야 사용자가 다음에
     * 뭘 눌러야 하는지 안다. code 는 그대로라 클라이언트 분기 계약은 바뀌지 않는다.
     */
    private final String userMessage;

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
        this(errorCode, detail, rejoinAvailableAt, nextChangeAvailableAt, confirmationToken, preview, null);
    }

    private BusinessException(ErrorCode errorCode, String detail, String rejoinAvailableAt,
                              String nextChangeAvailableAt, String confirmationToken, Object preview,
                              String userMessage) {
        super((userMessage != null) ? userMessage : errorCode.getMessage());
        this.userMessage = userMessage;
        this.errorCode = errorCode;
        this.detail = detail;
        this.rejoinAvailableAt = rejoinAvailableAt;
        this.nextChangeAvailableAt = nextChangeAvailableAt;
        this.confirmationToken = confirmationToken;
        this.preview = preview;
    }

    /**
     * 사유(reason)와 <b>그 사유에 맞춘 문구</b>를 함께 던진다 — 같은 code 안에서 원인이 여러 개일 때 쓴다.
     * 예: LOGIN_FAILED 는 PKCE 누락일 수도, 인가코드 만료일 수도 있는데 사용자가 할 일은 서로 다르다.
     */
    public static BusinessException withMessage(ErrorCode errorCode, String reason, String userMessage) {
        return new BusinessException(errorCode, reason, null, null, null, null, userMessage);
    }

    /** 실제로 내려보낼 사용자 문구 — 채워 넣은 게 있으면 그것, 없으면 ErrorCode 기본 문구. */
    public String getUserMessage() {
        return (userMessage != null) ? userMessage : errorCode.getMessage();
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