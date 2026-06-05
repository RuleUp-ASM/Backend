package com.ruleup.ruleup_backend.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 서비스 전역 에러 코드.
 * enum 상수의 "이름"이 그대로 응답 JSON의 error.code 문자열이 된다.
 * (ErrorResponse.of()가 errorCode.name()을 쓰기 때문)
 * => 따라서 상수 이름은 반드시 "API 명세서의 에러 코드"와 1:1로 똑같아야 한다.
 */
@Getter
public enum ErrorCode {

    // ===== 로그인 (4.1 / 4.2) =====
    LOGIN_FAILED(HttpStatus.BAD_REQUEST, "소셜 로그인에 실패했습니다."),
    LOGIN_PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "소셜 로그인 제공자에 연결할 수 없습니다."),

    // ===== 가입 세션 토큰 (signupToken) (4.3) =====
    SIGNUP_SESSION_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 가입 세션입니다."),
    SIGNUP_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "가입 세션이 만료되었습니다. 처음부터 다시 진행해주세요."),

    // ===== 닉네임 / 카테고리 / 약관 (4.3 / 4.6 / 4.9) =====
    NICKNAME_FORMAT_INVALID(HttpStatus.BAD_REQUEST, "닉네임 형식이 올바르지 않습니다."),
    NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    NICKNAME_CHANGE_LOCKED(HttpStatus.FORBIDDEN, "닉네임은 30일에 한 번만 변경할 수 있습니다."),
    CATEGORY_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 관심 카테고리입니다."),
    CATEGORY_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "관심 카테고리는 1~6개까지 선택할 수 있습니다."),
    AGREEMENT_REQUIRED(HttpStatus.BAD_REQUEST, "필수 약관에 동의해야 합니다."),

    // ===== 앱 토큰 (4.4 refresh / 보호 API) =====
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "세션이 만료되었습니다. 다시 로그인해주세요."),
    LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),

    // ===== 이미지 업로드 (4.10) =====
    IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "이미지 크기는 10MB를 초과할 수 없습니다."),
    IMAGE_INVALID_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "jpg 또는 png 이미지만 업로드할 수 있습니다."),
    IMAGE_CORRUPTED(HttpStatus.BAD_REQUEST, "이미지 파일이 손상되었습니다."),

    // ===== 공통 =====
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}