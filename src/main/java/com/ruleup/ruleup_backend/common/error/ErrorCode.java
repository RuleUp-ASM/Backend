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

    // ===== 챌린지 - 추천/생성 입력 검증 (3.1 / 3.2) =====
    TITLE_REQUIRED(HttpStatus.BAD_REQUEST, "챌린지 이름을 입력해주세요."),
    TITLE_TOO_LONG(HttpStatus.BAD_REQUEST, "챌린지 이름은 30자를 초과할 수 없습니다."),
    DESCRIPTION_TOO_LONG(HttpStatus.BAD_REQUEST, "설명은 200자를 초과할 수 없습니다."),
    AI_RECOMMENDATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "AI 추천에 실패했습니다. 직접 입력해주세요."),
    INVALID_CATEGORY(HttpStatus.BAD_REQUEST, "유효하지 않은 카테고리입니다."),
    INVALID_PARTICIPATION_TYPE(HttpStatus.BAD_REQUEST, "유효하지 않은 참여 방식입니다."),
    INVALID_ANONYMITY(HttpStatus.BAD_REQUEST, "유효하지 않은 공개 설정입니다."),
    VERIFICATION_METHOD_REQUIRED(HttpStatus.BAD_REQUEST, "인증 방식을 1개 이상 선택해야 합니다."),
    INVALID_VERIFICATION_METHOD(HttpStatus.BAD_REQUEST, "유효하지 않은 인증 방식입니다."),
    INVALID_REPEAT_DAY(HttpStatus.BAD_REQUEST, "유효하지 않은 반복 요일입니다."),
    INVALID_DURATION(HttpStatus.BAD_REQUEST, "기간(일)은 1 이상이어야 합니다."),
    INVALID_PENALTY(HttpStatus.BAD_REQUEST, "패널티 설정이 올바르지 않습니다."),
    INVALID_REWARD(HttpStatus.BAD_REQUEST, "보상 설정이 올바르지 않습니다."),
    START_DATE_REQUIRED(HttpStatus.BAD_REQUEST, "시작일을 입력해주세요."),
    INVALID_MIN_MANNER_TEMPERATURE(HttpStatus.BAD_REQUEST, "참여 기준 매너 온도는 생성자 본인의 매너 온도보다 높을 수 없습니다."),

    // ===== 챌린지 - 조회/수정/삭제 (3.3 / 3.4 / 3.5) =====
    CHALLENGE_NOT_FOUND(HttpStatus.NOT_FOUND, "챌린지를 찾을 수 없습니다."),
    NOT_CHALLENGE_OWNER(HttpStatus.FORBIDDEN, "챌린지 생성자만 수행할 수 있습니다."),
    CHALLENGE_NOT_EDITABLE(HttpStatus.CONFLICT, "시작된 챌린지는 수정/삭제할 수 없습니다."),

    // ===== 챌린지 - 참여/멤버 (3.6 / 3.7 / 3.8) =====
    MANNER_TEMPERATURE_BELOW_MINIMUM(HttpStatus.FORBIDDEN, "참여 기준 매너 온도를 충족하지 못했습니다."),
    ALREADY_JOINED(HttpStatus.CONFLICT, "이미 참여한 챌린지입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "멤버를 찾을 수 없습니다."),
    INVALID_MEMBER_ACTION(HttpStatus.BAD_REQUEST, "유효하지 않은 처리 동작입니다. (APPROVE / REJECT)"),

    // ===== 루틴 - 추천/생성 (제목→템플릿 매칭→인증방식 선택) =====
    ROUTINE_TITLE_REQUIRED(HttpStatus.BAD_REQUEST, "루틴 제목을 입력해주세요."),
    ROUTINE_TITLE_TOO_LONG(HttpStatus.BAD_REQUEST, "루틴 제목은 100자를 초과할 수 없습니다."),
    ROUTINE_DESCRIPTION_TOO_LONG(HttpStatus.BAD_REQUEST, "루틴 설명은 255자를 초과할 수 없습니다."),
    ROUTINE_TEMPLATE_NOT_FOUND(HttpStatus.BAD_REQUEST, "선택한 루틴 템플릿을 찾을 수 없습니다."),
    ROUTINE_METHOD_REQUIRED(HttpStatus.BAD_REQUEST, "인증 방식(AUTO/MANUAL)을 선택해주세요."),
    ROUTINE_AUTO_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "이 루틴은 자동 인증을 지원하지 않습니다."),
    ROUTINE_PERMISSION_REQUIRED(HttpStatus.BAD_REQUEST, "자동 인증에 필요한 권한이 허용되지 않았습니다."),
    INVALID_ROUTINE_PARAM(HttpStatus.BAD_REQUEST, "목표값이 올바르지 않습니다."),

    // ===== 알림 =====
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),

    // ===== 인증 sync (§3.1) =====
    SYNC_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "sync 요청 간격이 너무 짧습니다."),
    INVALID_SIGNAL_PAYLOAD(HttpStatus.BAD_REQUEST, "인증 신호 페이로드가 올바르지 않습니다."),

    NOT_CHALLENGE_MEMBER(HttpStatus.FORBIDDEN, "챌린지 참여자만 접근할 수 있습니다."),
    ALREADY_VERIFIED(HttpStatus.CONFLICT, "이미 인증된 날짜입니다."),
    INVALID_TARGET_DATE(HttpStatus.BAD_REQUEST, "유효하지 않은 대상 날짜입니다."),
    IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "사진 인증은 이미지가 필요합니다."),

    // ===== 인증 v2 — 예비 폴백 / 셋업 / 내 위치 / 장소 검색 (§9·§11) =====
    FALLBACK_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "예비 수동 인증은 주 1회만 사용할 수 있습니다."),
    GEOFENCE_NOT_CONFIGURED(HttpStatus.BAD_REQUEST, "인증 장소(앵커)가 설정되지 않았습니다."),
    LOCATION_LOCKED_IN_WINDOW(HttpStatus.CONFLICT, "인증 윈도우 중에는 위치를 변경할 수 없습니다. 변경은 익일부터 적용됩니다."),
    LOCATION_CHANGE_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "위치 변경은 일정 기간(쿨다운) 후에 가능합니다."),
    INVALID_ANCHOR(HttpStatus.BAD_REQUEST, "앵커 설정이 올바르지 않습니다.(반경 0.5~5km, 최대 10개)"),
    INVALID_QUERY(HttpStatus.BAD_REQUEST, "검색어가 올바르지 않습니다."),
    PLACE_SEARCH_RATE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "장소 검색 요청이 너무 많습니다."),

    // ===== 공통 =====
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}