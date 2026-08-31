package com.ruleup.ruleup_backend.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 에러 응답의 공통 본문 (테크 스펙 3.5).
 * 예: { "code": "NICKNAME_DUPLICATED", "message": "이미 사용 중인 닉네임입니다." }
 * reason: 클라이언트 분기용 선택 필드(예: JOIN_BLOCKED → PRIVATE_INVITE_ONLY/FULL/TIER_GATE). 없으면 직렬화 생략.
 *
 * <p>아래 둘은 "그 코드를 받은 클라가 곧바로 다음 행동을 정할 수 있어야 하는" 값이라 본문에 함께 싣는다.
 * 해당 코드가 아니면 필드 자체가 직렬화되지 않는다.
 *  - rejoinAvailableAt      : JOIN_BLOCKED + REJOIN_COOLDOWN — 재입장 가능 시각
 *  - nextChangeAvailableAt  : SETTING_CHANGE_LIMIT — 다음 변경 가능 시각(다음 달 1일 00:00 KST)
 *  - confirmationToken/preview : CONFIRMATION_REQUIRED — 2단계 확인. 무엇을 확인하는지(preview)와
 *    그 확인에 한해 유효한 토큰을 함께 내려, 클라가 재확인 화면을 그대로 띄울 수 있게 한다
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
        String rejoinAvailableAt,

        @io.swagger.v3.oas.annotations.media.Schema(
                description = "다음 변경 가능 시각(ISO-8601, KST) — SETTING_CHANGE_LIMIT 일 때만 실린다.",
                example = "2026-09-01T00:00:00+09:00")
        String nextChangeAvailableAt,

        @io.swagger.v3.oas.annotations.media.Schema(
                description = "이 요청에 한해 유효한 확인 토큰 — CONFIRMATION_REQUIRED 일 때만 실린다.")
        String confirmationToken,

        @io.swagger.v3.oas.annotations.media.Schema(
                description = "재확인 화면에 보여줄 요약(대상·사유·기간·영향 인원 등)")
        Object preview) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), null, null, null, null, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String reason) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), reason, null, null, null, null);
    }

    /** 예외가 실어 보낸 부가 필드까지 그대로 옮긴다. 없는 값은 null 이라 직렬화에서 빠진다. */
    public static ErrorResponse of(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return new ErrorResponse(code.name(), code.getMessage(),
                e.getDetail(), e.getRejoinAvailableAt(), e.getNextChangeAvailableAt(),
                e.getConfirmationToken(), e.getPreview());
    }
}
