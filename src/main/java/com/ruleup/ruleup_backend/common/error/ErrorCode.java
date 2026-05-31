package com.ruleup.ruleup_backend.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 서비스 전역 에러 코드 (테크 스펙 3.4).
 * 각 코드는 HTTP 상태 + 기본 메시지를 함께 들고 있다.
 * 모든 에러 응답은 여기 정의된 code만 사용한다.
 */
@Getter
public enum ErrorCode {

    // OAuth
    OAUTH_CODE_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 인증 코드입니다."),
    OAUTH_PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "소셜 로그인 제공자에 연결할 수 없습니다."),

    // 토큰
    ACCESS_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "인증 헤더가 없습니다."),
    ACCESS_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 액세스 토큰입니다."),
    ACCESS_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "액세스 토큰이 만료되었습니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 만료되었습니다."),
    REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "무효화된 리프레시 토큰입니다."),
    SIGNUP_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 가입 토큰입니다."),
    SIGNUP_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "가입 토큰이 만료되었습니다. 처음부터 다시 진행해주세요."),

    // 가입 / 프로필
    NICKNAME_INVALID(HttpStatus.BAD_REQUEST, "닉네임 형식이 올바르지 않습니다."),
    NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    NICKNAME_CHANGE_TOO_SOON(HttpStatus.FORBIDDEN, "닉네임은 30일에 한 번만 변경할 수 있습니다."),
    INTEREST_CATEGORY_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 관심 카테고리입니다."),
    AGREEMENT_REQUIRED(HttpStatus.BAD_REQUEST, "필수 약관에 동의해야 합니다."),

    // 이미지 업로드
    IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "이미지 크기는 10MB를 초과할 수 없습니다."),
    IMAGE_INVALID_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "jpg 또는 png 이미지만 업로드할 수 있습니다."),
    IMAGE_CORRUPTED(HttpStatus.BAD_REQUEST, "이미지 파일이 손상되었습니다."),

    // 공통
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}