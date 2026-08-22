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
    INVALID_REDIRECT_URI(HttpStatus.BAD_REQUEST, "redirectUri가 올바르지 않습니다."),
    ACCOUNT_BANNED(HttpStatus.FORBIDDEN, "영구 정지된 계정입니다."),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "잠금 상태의 계정은 이 기능을 사용할 수 없습니다."),
    /** reason 에 그 계정의 소셜 제공자(KAKAO/GOOGLE)를 실어 보낸다 — 클라가 "카카오로 로그인" 까지 안내할 수 있게. */
    INSTALLATION_ALREADY_REGISTERED(HttpStatus.FORBIDDEN,
            "이 기기는 다른 계정으로 가입한 이력이 있어요. 처음 가입할 때 쓰신 계정으로 로그인해주세요."),

    // ===== 가입 세션 토큰 (signupToken) (4.3) =====
    // 계약: 만료/위조 모두 400 INVALID_SIGNUP_TOKEN 로 단일화.
    INVALID_SIGNUP_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 가입 세션입니다. 처음부터 다시 진행해주세요."),

    // ===== 기기 정보 (deviceInfo) (4.1 / 4.3) =====
    INVALID_DEVICE_INFO(HttpStatus.BAD_REQUEST, "기기 정보(deviceInfo)가 누락되었거나 형식이 올바르지 않습니다."),

    // ===== 닉네임 / 카테고리 / 약관 / 온보딩 (4.3 / 4.6 / 4.9) =====
    NICKNAME_FORMAT_INVALID(HttpStatus.BAD_REQUEST, "닉네임 형식이 올바르지 않습니다."),
    NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    NICKNAME_CHANGE_LOCKED(HttpStatus.FORBIDDEN, "닉네임은 30일에 한 번만 변경할 수 있습니다."),
    CATEGORY_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 관심 카테고리입니다."),
    CATEGORY_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "관심 카테고리는 최대 6개까지 선택할 수 있습니다."),
    INTEREST_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "관심 카테고리는 0~6개까지 선택할 수 있습니다."),
    REQUIRED_AGREEMENT_MISSING(HttpStatus.BAD_REQUEST, "필수 약관(이용약관·개인정보·위치기반)에 모두 동의해야 합니다."),
    BIRTHDATE_INVALID(HttpStatus.BAD_REQUEST, "생년월일 형식이 올바르지 않습니다. (YYYY-MM-DD)"),
    BIRTHDATE_UNDERAGE(HttpStatus.BAD_REQUEST, "만 14세 미만은 가입할 수 없습니다."),
    GENDER_REQUIRED(HttpStatus.BAD_REQUEST, "성별 값이 누락되었거나 올바르지 않습니다. (MALE/FEMALE/NON_BINARY)"),
    CONFIRM_PHRASE_MISMATCH(HttpStatus.BAD_REQUEST, "탈퇴 확인 문구가 일치하지 않습니다."),

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
    INVALID_WEEKLY_COUNT(HttpStatus.BAD_REQUEST, "주간 수행 횟수는 1~7이어야 합니다."),
    INVALID_DURATION(HttpStatus.BAD_REQUEST, "기간(일)은 1 이상이어야 합니다."),
    INVALID_PENALTY(HttpStatus.BAD_REQUEST, "패널티 설정이 올바르지 않습니다."),
    INVALID_REWARD(HttpStatus.BAD_REQUEST, "보상 설정이 올바르지 않습니다."),
    START_DATE_REQUIRED(HttpStatus.BAD_REQUEST, "시작일을 입력해주세요."),
    INVALID_MIN_MANNER_TEMPERATURE(HttpStatus.BAD_REQUEST, "참여 기준 매너 온도는 생성자 본인의 매너 온도보다 높을 수 없습니다."),
    MIN_TEMP_EXCEEDS_OWNER(HttpStatus.BAD_REQUEST, "가입 기준 온도는 생성자 본인의 온도를 초과할 수 없습니다."),
    /** 동시 참여 한도 초과 — 생성 경로. 가입 경로는 JOIN_BLOCKED + reason=FREE_LIMIT 로 내려간다. */
    CHALLENGE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "함께 진행할 수 있는 챌린지 수를 넘었어요. 진행 중인 챌린지를 마치고 새로 만들어 주세요."),
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
    /**
     * 가입 거절 단일 코드. 어떤 게이트에 걸렸는지는 reason 으로 내려간다
     * (PRIVATE_INVITE_ONLY / REJOIN_COOLDOWN / BANNED / FREE_LIMIT / FULL / TIER_GATE /
     * ALREADY_JOINED / CHALLENGE_COMPLETED — {@code JoinBlockReason}).
     * REJOIN_COOLDOWN 이면 rejoinAvailableAt 이 함께 실린다.
     */
    JOIN_BLOCKED(HttpStatus.CONFLICT, "지금은 이 챌린지에 들어갈 수 없어요."),
    MANNER_TEMPERATURE_BELOW_MINIMUM(HttpStatus.FORBIDDEN, "참여 기준 매너 온도를 충족하지 못했습니다."),
    ALREADY_JOINED(HttpStatus.CONFLICT, "이미 참여한 챌린지입니다."),
    REJOIN_FORBIDDEN(HttpStatus.CONFLICT, "탈퇴한 챌린지에는 다시 참여할 수 없습니다."),
    REJOIN_NOT_AVAILABLE(HttpStatus.CONFLICT, "강퇴 후 재참여 대기 기간이 아직 끝나지 않았습니다."),
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
    ROUTINE_DESCRIPTION_REQUIRED(HttpStatus.BAD_REQUEST, "루틴 설명을 입력해주세요."),
    DRAFT_NOT_FOUND(HttpStatus.BAD_REQUEST, "챌린지 초안 정보를 찾을 수 없어요. 처음부터 다시 만들어주세요."),
    DRAFT_EXPIRED(HttpStatus.BAD_REQUEST, "챌린지 초안이 오래되어 사용할 수 없어요. 처음부터 다시 만들어주세요."),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "요청을 처리할 수 없어요. 앱을 최신 버전으로 업데이트한 뒤 다시 시도해주세요."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "이미 처리 중인 요청이 있어요. 잠시 후 다시 확인해주세요."),
    CAPACITY_REQUIRED(HttpStatus.BAD_REQUEST, "그룹 챌린지는 모집 인원을 정해야 해요."),
    CAPACITY_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "모집 인원은 1명부터 10,000명까지 정할 수 있어요."),
    MIN_TIER_EXCEEDS_OWNER(HttpStatus.BAD_REQUEST, "최소 입장 티어는 내 티어보다 높게 정할 수 없어요."),
    INVALID_PERIOD(HttpStatus.BAD_REQUEST, "챌린지 기간을 다시 확인해주세요."),
    INVALID_IMAGE_URL(HttpStatus.BAD_REQUEST, "사용할 수 없는 이미지예요. 이미지를 다시 업로드해주세요."),
    IMAGE_NOT_OWNED(HttpStatus.FORBIDDEN, "내가 업로드한 이미지만 사용할 수 있어요."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 곳에서 챌린지 정보가 바뀌었어요. 새로고침 후 다시 시도해주세요."),
    INVALID_FIELD_VALUE(HttpStatus.BAD_REQUEST, "입력값을 다시 확인해주세요."),
    CAPACITY_BELOW_CURRENT(HttpStatus.BAD_REQUEST, "모집 인원은 현재 참여 인원보다 적게 줄일 수 없어요."),
    MODERATION_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "수정이 잠시 제한되었어요. 1시간 뒤에 다시 시도해주세요."),
    ROUTINE_DESCRIPTION_TOO_LONG(HttpStatus.BAD_REQUEST, "루틴 설명은 200자를 초과할 수 없습니다."),
    TEMPLATE_ID_REQUIRED(HttpStatus.BAD_REQUEST, "추천 루틴을 선택해주세요."),
    TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "선택한 루틴을 찾을 수 없어요. 다른 루틴을 골라주세요."),
    ROUTINE_TEMPLATE_NOT_FOUND(HttpStatus.BAD_REQUEST, "선택한 루틴 템플릿을 찾을 수 없습니다."),
    ROUTINE_METHOD_REQUIRED(HttpStatus.BAD_REQUEST, "인증 방식(AUTO/MANUAL)을 선택해주세요."),
    ROUTINE_AUTO_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "이 루틴은 자동 인증을 지원하지 않습니다."),
    ROUTINE_PERMISSION_REQUIRED(HttpStatus.BAD_REQUEST, "자동 인증에 필요한 권한이 모두 허용되지 않았습니다."),
    INVALID_ROUTINE_PARAM(HttpStatus.BAD_REQUEST, "목표값이 올바르지 않습니다."),

    // ===== 마이프로필 (마이 홈·캘린더·통계·평판·초대) =====
    INVALID_CALENDAR_MONTH(HttpStatus.BAD_REQUEST, "월 형식이 올바르지 않습니다. (YYYY-MM)"),
    INVALID_CALENDAR_DATE(HttpStatus.BAD_REQUEST, "날짜 형식이 올바르지 않습니다. (YYYY-MM-DD)"),
    INVALID_STATS_PERIOD(HttpStatus.BAD_REQUEST, "통계 기간이 올바르지 않습니다. (WEEKLY / MONTHLY / YEARLY)"),

    // ===== 방 내부(스레드·랭킹·방 홈) =====
    // 공지·댓글 코드(NOTICE_*/COMMENT_*/REPLY_DEPTH_EXCEEDED)는 Phase 2 이관과 함께 제거했다.
    NOT_A_MEMBER(HttpStatus.FORBIDDEN, "챌린지 멤버만 접근할 수 있습니다."),
    INVALID_RANKING_MODE(HttpStatus.BAD_REQUEST, "랭킹 모드가 올바르지 않습니다."),

    // ===== 챌린지 방 운영 =====
    NOT_PRIVATE_CHALLENGE(HttpStatus.CONFLICT, "비공개 그룹 챌린지만 초대 링크를 만들 수 있습니다."),
    KICK_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "강퇴 사유를 10자 이상 500자 이하로 입력해주세요."),
    CANNOT_KICK_SELF(HttpStatus.BAD_REQUEST, "방장은 본인을 강퇴할 수 없습니다."),
    TARGET_NOT_MEMBER(HttpStatus.NOT_FOUND, "대상 사용자는 현재 챌린지 멤버가 아닙니다."),
    CANNOT_TRANSFER_TO_SELF(HttpStatus.BAD_REQUEST, "본인에게 방장 권한을 이전할 수 없습니다."),
    OWNER_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용자 방장이 존재합니다."),

    // ===== 신고·차단·다른 사용자 프로필 =====
    INVALID_REPORT_TARGET(HttpStatus.BAD_REQUEST, "신고 대상이 올바르지 않습니다."),
    INVALID_REPORT_REASON(HttpStatus.BAD_REQUEST, "신고 사유가 올바르지 않습니다."),
    DETAIL_REQUIRED(HttpStatus.BAD_REQUEST, "신고 상세 내용을 입력해주세요."),
    CANNOT_REPORT_SELF(HttpStatus.BAD_REQUEST, "본인을 신고할 수 없습니다."),
    REPORT_SUSPENDED(HttpStatus.FORBIDDEN, "신고 기능이 일시적으로 제한되었습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    BLACKLIST_ENTRY_NOT_FOUND(HttpStatus.NOT_FOUND, "차단 내역을 찾을 수 없습니다."),

    // ===== 알림 =====
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
    INVALID_SETTING_KEY(HttpStatus.BAD_REQUEST, "알림 설정 항목이 올바르지 않습니다."),

    // ===== 인증 sync (§3.1) =====
    SYNC_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "sync 요청 간격이 너무 짧습니다."),
    INVALID_SIGNAL_PAYLOAD(HttpStatus.BAD_REQUEST, "인증 신호 페이로드가 올바르지 않습니다."),
    SYNC_PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "sync 누적 일괄 상한을 초과했습니다. 분할 재전송하세요."),

    NOT_CHALLENGE_MEMBER(HttpStatus.FORBIDDEN, "챌린지 참여자만 접근할 수 있습니다."),
    ALREADY_VERIFIED(HttpStatus.CONFLICT, "이미 인증된 날짜입니다."),

    // ===== 수동 인증 제출 / 취소 =====
    NOT_MANUAL_CHALLENGE(HttpStatus.CONFLICT, "직접 체크로 인증하는 챌린지가 아니에요."),
    NOT_MANUAL_VERIFICATION(HttpStatus.CONFLICT, "자동으로 판정된 인증은 취소할 수 없어요."),
    CANCEL_WINDOW_CLOSED(HttpStatus.CONFLICT, "오늘이 지나서 취소할 수 없어요."),

    // ===== 이의 제기 처리 (OWNER/MANAGER) =====
    VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "인증 제출을 찾을 수 없습니다."),
    ALREADY_DECIDED(HttpStatus.CONFLICT, "이미 승인/거절된 제출입니다."),
    INVALID_TARGET_DATE(HttpStatus.BAD_REQUEST, "유효하지 않은 대상 날짜입니다."),
    CONTENT_REQUIRED(HttpStatus.BAD_REQUEST, "글 내용을 입력해주세요."),
    NOT_CHALLENGE_ADMIN(HttpStatus.FORBIDDEN, "방장 또는 공동 관리자만 처리할 수 있습니다."),
    INVALID_DECISION(HttpStatus.BAD_REQUEST, "유효하지 않은 처리 동작입니다. (APPROVE / REJECT)"),
    // 이의 제기(§8.7)
    OBJECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "이의 제기를 찾을 수 없습니다."),
    OBJECTION_WINDOW_CLOSED(HttpStatus.CONFLICT, "이의 제기 창(3일)이 지났습니다."),
    NOT_OBJECTIONABLE(HttpStatus.CONFLICT, "이의 제기할 수 없는 상태입니다(잠정 실패가 아니거나 솔로 챌린지)."),
    ALREADY_OBJECTED(HttpStatus.CONFLICT, "이미 이의 제기한 날짜입니다."),
    UNSUPPORTED_OBJECTION_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 이의 제기 유형입니다. (FAILURE만 지원)"),

    // ===== 인증 셋업 — 내 인증 장소 / 측정 대상 앱 =====
    GEOFENCE_NOT_CONFIGURED(HttpStatus.BAD_REQUEST, "인증 장소가 아직 설정되지 않았어요."),
    LOCATION_LOCKED_IN_WINDOW(HttpStatus.CONFLICT, "인증 시간 중에는 장소를 바꿀 수 없어요. 내일 다시 시도해 주세요."),
    INVALID_ANCHOR(HttpStatus.BAD_REQUEST, "인증 장소가 올바르지 않아요. 지도에서 다시 선택해 주세요."),
    ANCHOR_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "인증 장소는 최대 3개까지 등록할 수 있어요."),
    SCREENTIME_NOT_CONFIGURED(HttpStatus.BAD_REQUEST, "측정할 앱이 아직 설정되지 않았어요."),
    INVALID_APP(HttpStatus.BAD_REQUEST, "선택한 앱이 올바르지 않아요. 최대 10개까지, 같은 앱은 한 번만 고를 수 있어요."),
    /** 앵커·대상 앱 변경은 월 1회(매월 1일 00:00 KST 리셋). 응답에 nextChangeAvailableAt 을 함께 내려준다. */
    SETTING_CHANGE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "이 설정은 한 달에 한 번만 바꿀 수 있어요."),

    // ===== 챌린지 탐색 (search 스펙) =====
    INVALID_SORT_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 정렬이에요."),
    INVALID_FILTER_VALUE(HttpStatus.BAD_REQUEST, "선택할 수 없는 필터 값이에요."),
    CURSOR_INVALID(HttpStatus.BAD_REQUEST, "목록을 처음부터 다시 불러와 주세요."),
    NOT_CLONEABLE(HttpStatus.FORBIDDEN, "이 챌린지는 템플릿으로 가져올 수 없어요."),
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
