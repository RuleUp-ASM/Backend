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
    // 계약: 만료/위조 모두 400 INVALID_SIGNUP_TOKEN 로 단일화.
    INVALID_SIGNUP_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 가입 세션입니다. 처음부터 다시 진행해주세요."),

    // ===== 기기 정보 (deviceInfo) (4.1 / 4.3) =====
    INVALID_DEVICE_INFO(HttpStatus.BAD_REQUEST, "기기 정보(deviceInfo)가 누락되었거나 형식이 올바르지 않습니다."),

    // ===== 닉네임 / 카테고리 / 약관 (4.3 / 4.6 / 4.9) =====
    NICKNAME_FORMAT_INVALID(HttpStatus.BAD_REQUEST, "닉네임 형식이 올바르지 않습니다."),
    NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
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
    IMAGE_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "부적절한 이미지로 업로드가 차단되었습니다."),

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
    MIN_TEMP_EXCEEDS_OWNER(HttpStatus.BAD_REQUEST, "가입 기준 온도는 생성자 본인의 온도를 초과할 수 없습니다."),
    MAX_PARTICIPANTS_REQUIRED(HttpStatus.BAD_REQUEST, "그룹 챌린지는 최대 참여 인원을 지정해야 합니다."),
    MAX_PARTICIPANTS_BELOW_CURRENT(HttpStatus.BAD_REQUEST, "최대 참여 인원을 현재 참여 인원 미만으로 줄일 수 없습니다."),

    // ===== 챌린지 - 조회/수정/삭제 (3.3 / 3.4 / 3.5) =====
    CHALLENGE_NOT_FOUND(HttpStatus.NOT_FOUND, "챌린지를 찾을 수 없습니다."),
    NOT_CHALLENGE_OWNER(HttpStatus.FORBIDDEN, "챌린지 생성자만 수행할 수 있습니다."),
    CHALLENGE_NOT_EDITABLE(HttpStatus.CONFLICT, "시작된 챌린지는 수정/삭제할 수 없습니다."),
    // 모더레이션 게이트(§5.1) / 삭제 정책(§5.8)
    CHALLENGE_UNDER_REVIEW(HttpStatus.CONFLICT, "검수 중인 챌린지에는 참여할 수 없습니다."),
    CHALLENGE_HAS_MEMBERS(HttpStatus.CONFLICT, "다른 참여자가 있는 챌린지는 삭제할 수 없습니다."),
    DELETE_LOCKED(HttpStatus.CONFLICT, "생성 후 7일 이내이거나 계획 기간이 7일 미만이면 삭제할 수 없습니다."),
    CHALLENGE_NAME_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "사용할 수 없는 챌린지 이름입니다."),

    // ===== 챌린지 - 참여/탈퇴/멤버 (§5·§6·§7) =====
    MANNER_TEMPERATURE_BELOW_MINIMUM(HttpStatus.FORBIDDEN, "참여 기준 매너 온도를 충족하지 못했습니다."),
    ALREADY_JOINED(HttpStatus.CONFLICT, "이미 참여한 챌린지입니다."),
    REJOIN_FORBIDDEN(HttpStatus.CONFLICT, "탈퇴한 챌린지에는 다시 참여할 수 없습니다."),
    CHALLENGE_FULL(HttpStatus.CONFLICT, "정원이 가득 찼습니다."),
    CHALLENGE_COMPLETED(HttpStatus.CONFLICT, "종료된 챌린지입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "멤버를 찾을 수 없습니다."),
    OWNER_CANNOT_LEAVE(HttpStatus.FORBIDDEN, "방장은 탈퇴할 수 없습니다. 참여자가 있으면 위임 후, 없으면 삭제로 진행하세요."),
    // 역할 임명/해제(§7-1)
    CANNOT_CHANGE_OWNER_ROLE(HttpStatus.BAD_REQUEST, "OWNER 역할은 이 API로 변경할 수 없습니다. 위임을 사용하세요."),
    ALREADY_IN_ROLE(HttpStatus.CONFLICT, "이미 해당 역할입니다."),
    INVALID_MEMBER_ACTION(HttpStatus.BAD_REQUEST, "유효하지 않은 처리 동작입니다."),
    // 방장 위임(§7-2)
    TARGET_NOT_MANAGER(HttpStatus.BAD_REQUEST, "위임 대상은 공동 관리자여야 합니다."),
    DELEGATION_ALREADY_PENDING(HttpStatus.CONFLICT, "이미 진행 중인 위임 요청이 있습니다."),
    DELEGATION_NOT_FOUND(HttpStatus.NOT_FOUND, "위임 요청을 찾을 수 없습니다."),
    DELEGATION_EXPIRED(HttpStatus.GONE, "만료된 위임 요청입니다."),
    DELEGATION_ALREADY_RESOLVED(HttpStatus.CONFLICT, "이미 처리된 위임 요청입니다."),
    NOT_DELEGATION_TARGET(HttpStatus.FORBIDDEN, "위임 대상자만 수행할 수 있습니다."),
    INVALID_DELEGATION_ACTION(HttpStatus.BAD_REQUEST, "유효하지 않은 위임 동작입니다. (ACCEPT / REJECT / CANCEL)"),

    // ===== 루틴 - 추천/생성 (제목→템플릿 매칭→인증방식 선택) =====
    RECOMMENDATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "추천 요청이 너무 잦습니다. 잠시 후 다시 시도해주세요."),
    ROUTINE_TITLE_REQUIRED(HttpStatus.BAD_REQUEST, "루틴 제목을 입력해주세요."),
    ROUTINE_TITLE_TOO_LONG(HttpStatus.BAD_REQUEST, "루틴 제목은 100자를 초과할 수 없습니다."),
    ROUTINE_DESCRIPTION_TOO_LONG(HttpStatus.BAD_REQUEST, "루틴 설명은 255자를 초과할 수 없습니다."),
    ROUTINE_TEMPLATE_NOT_FOUND(HttpStatus.BAD_REQUEST, "선택한 루틴 템플릿을 찾을 수 없습니다."),
    ROUTINE_METHOD_REQUIRED(HttpStatus.BAD_REQUEST, "인증 방식(AUTO/MANUAL)을 선택해주세요."),
    ROUTINE_AUTO_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "이 루틴은 자동 인증을 지원하지 않습니다."),
    ROUTINE_PERMISSION_REQUIRED(HttpStatus.BAD_REQUEST, "자동 인증에 필요한 권한이 모두 허용되지 않았습니다."),
    INVALID_ROUTINE_PARAM(HttpStatus.BAD_REQUEST, "목표값이 올바르지 않습니다."),

    // ===== 마이프로필 (마이 홈·캘린더·통계·평판·초대) =====
    INVALID_CALENDAR_MONTH(HttpStatus.BAD_REQUEST, "월 형식이 올바르지 않습니다. (YYYY-MM)"),
    INVALID_CALENDAR_DATE(HttpStatus.BAD_REQUEST, "날짜 형식이 올바르지 않습니다. (YYYY-MM-DD)"),
    INVALID_STATS_PERIOD(HttpStatus.BAD_REQUEST, "통계 기간이 올바르지 않습니다. (WEEKLY / MONTHLY / YEARLY)"),

    // ===== 알림 =====
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),

    // ===== 인증 sync (§3.1) =====
    SYNC_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "sync 요청 간격이 너무 짧습니다."),
    INVALID_SIGNAL_PAYLOAD(HttpStatus.BAD_REQUEST, "인증 신호 페이로드가 올바르지 않습니다."),
    SYNC_PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "sync 누적 일괄 상한을 초과했습니다. 분할 재전송하세요."),

    NOT_CHALLENGE_MEMBER(HttpStatus.FORBIDDEN, "챌린지 참여자만 접근할 수 있습니다."),
    ALREADY_VERIFIED(HttpStatus.CONFLICT, "이미 인증된 날짜입니다."),

    // ===== 폴백 승인 / 이의 제기 처리 (§8.7 / §10.2 — OWNER/MANAGER) =====
    VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "인증 제출을 찾을 수 없습니다."),
    ALREADY_DECIDED(HttpStatus.CONFLICT, "이미 승인/거절된 제출입니다."),
    NOT_PENDING_APPROVAL(HttpStatus.CONFLICT, "승인 대상(예비 폴백)이 아닙니다."),
    INVALID_TARGET_DATE(HttpStatus.BAD_REQUEST, "유효하지 않은 대상 날짜입니다."),
    IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "사진 인증은 이미지가 필요합니다."),
    CONTENT_REQUIRED(HttpStatus.BAD_REQUEST, "글 내용을 입력해주세요."),
    NOT_CHALLENGE_ADMIN(HttpStatus.FORBIDDEN, "방장 또는 공동 관리자만 처리할 수 있습니다."),
    INVALID_DECISION(HttpStatus.BAD_REQUEST, "유효하지 않은 처리 동작입니다. (APPROVE / REJECT)"),
    VERIFICATION_WINDOW_CLOSED(HttpStatus.CONFLICT, "제출 기한이 지났습니다."),
    // 이의 제기(§8.7)
    OBJECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "이의 제기를 찾을 수 없습니다."),
    OBJECTION_WINDOW_CLOSED(HttpStatus.CONFLICT, "이의 제기 창(3일)이 지났습니다."),
    NOT_OBJECTIONABLE(HttpStatus.CONFLICT, "이의 제기할 수 없는 상태입니다(잠정 실패가 아니거나 솔로 챌린지)."),
    ALREADY_OBJECTED(HttpStatus.CONFLICT, "이미 이의 제기한 날짜입니다."),
    UNSUPPORTED_OBJECTION_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 이의 제기 유형입니다. (FAILURE만 지원)"),

    // ===== 인증 v2 — 예비 폴백 / 셋업 / 내 위치 / 장소 검색 (§9·§11) =====
    FALLBACK_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "예비 수동 인증은 주 1회만 사용할 수 있습니다."),
    GEOFENCE_NOT_CONFIGURED(HttpStatus.BAD_REQUEST, "인증 장소(앵커)가 설정되지 않았습니다."),
    LOCATION_LOCKED_IN_WINDOW(HttpStatus.CONFLICT, "인증 윈도우 중에는 위치를 변경할 수 없습니다. 변경은 익일부터 적용됩니다."),
    LOCATION_CHANGE_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "위치 변경은 일정 기간(쿨다운) 후에 가능합니다."),
    INVALID_ANCHOR(HttpStatus.BAD_REQUEST, "앵커 설정이 올바르지 않습니다.(반경 0.5~5km, 최대 10개)"),
    SCREENTIME_NOT_CONFIGURED(HttpStatus.BAD_REQUEST, "측정 대상 앱이 설정되지 않았습니다."),
    INVALID_APP(HttpStatus.BAD_REQUEST, "대상 앱이 올바르지 않습니다.(패키지명 형식·중복·1~10개)"),
    SCREENTIME_CHANGE_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "대상 앱 변경은 일정 기간(쿨다운) 후에 가능합니다."),

    // ===== 챌린지 탐색 (search 스펙) =====
    INVALID_SORT_TYPE(HttpStatus.BAD_REQUEST, "정의되지 않은 정렬 키입니다."),
    INVALID_FILTER_VALUE(HttpStatus.BAD_REQUEST, "정의되지 않은 필터 값입니다."),
    CURSOR_INVALID(HttpStatus.BAD_REQUEST, "손상되었거나 만료된 커서입니다. 첫 페이지부터 다시 요청하세요."),
    INVALID_QUERY(HttpStatus.BAD_REQUEST, "검색어가 올바르지 않습니다."),
    PLACE_SEARCH_RATE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "장소 검색 요청이 너무 많습니다."),

    // ===== 감시자(watcher) (§5.9 / §11.4) =====
    WATCHER_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "무료 감시자 한도(3명)를 초과했습니다."),
    WATCHER_BLOCKED(HttpStatus.CONFLICT, "수신거부 이력으로 30일간 재초대할 수 없습니다."),
    WATCHER_NOT_FOUND(HttpStatus.NOT_FOUND, "감시자를 찾을 수 없습니다."),
    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "초대를 찾을 수 없습니다."),
    INVITATION_EXPIRED(HttpStatus.GONE, "초대가 만료되었습니다."),
    ALREADY_CONSENTED(HttpStatus.CONFLICT, "이미 수락한 초대입니다."),
    OTP_INVALID(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다."),
    OTP_EXPIRED(HttpStatus.GONE, "인증번호가 만료되었습니다."),
    OTP_RESEND_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 인증번호를 요청해주세요."),
    INVALID_PHONE(HttpStatus.BAD_REQUEST, "휴대폰 번호 형식이 올바르지 않습니다."),
    CONSENT_REQUIRED(HttpStatus.BAD_REQUEST, "수신 동의가 필요합니다."),
    UNSUBSCRIBE_TOKEN_INVALID(HttpStatus.NOT_FOUND, "유효하지 않은 수신거부 링크입니다."),
    UNSUBSCRIBE_TOKEN_EXPIRED(HttpStatus.GONE, "만료된 수신거부 링크입니다."),

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