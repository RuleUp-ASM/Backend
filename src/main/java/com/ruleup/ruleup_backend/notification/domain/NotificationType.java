package com.ruleup.ruleup_backend.notification.domain;

/** 알림 종류. enum 이름이 응답 JSON의 type 문자열이 된다. */
public enum NotificationType {
    /** 닉네임이 검수에서 거절됨 → "닉네임을 바꿔주세요" */
    NICKNAME_REJECTED,
    /** 프로필 사진이 검수에서 거절됨 → "프로필 사진을 바꿔주세요" */
    PROFILE_IMAGE_REJECTED,
    /** 챌린지 이름이 검수에서 거절됨 → "챌린지 이름을 바꿔주세요"(1시간 수정창) */
    CHALLENGE_NAME_REJECTED,
    /** 거절된 챌린지가 1시간 내 미수정으로 영구 닫힘 → "챌린지가 닫혔어요" */
    CHALLENGE_CLOSED,
    /** 챌린지가 검수를 통과해 공개됨 → 생성자에게 "챌린지가 공개되었어요" */
    CHALLENGE_APPROVED,
    /** 그룹 챌린지에 새 참여 신청 도착 → 생성자(방장)에게 "새 참여 신청이 있어요" */
    CHALLENGE_JOIN_REQUESTED,
    /** 참여 신청이 승인됨 → 신청자에게 "참여가 승인되었어요" */
    CHALLENGE_MEMBER_APPROVED,
    /** 참여 신청이 거절됨 → 신청자에게 "참여가 거절되었어요" */
    CHALLENGE_MEMBER_REJECTED,
    /** 예비 폴백 인증이 방장 승인됨 → 제출자에게 "예비 인증이 승인되었어요" */
    FALLBACK_APPROVED,
    /** 예비 폴백 인증이 방장 거절됨 → 제출자에게 "예비 인증이 거절되었어요" */
    FALLBACK_REJECTED,
    /** 감시 대상이 루틴을 실패함 → 감시자(유저)에게 인앱 통지 */
    WATCHER_ROUTINE_FAILED,
    /** 방장이 공지를 등록(또는 재확인 필요 수정)함 → ACTIVE 멤버(작성자 제외)에게 인앱 통지 */
    NOTICE_CREATED,
    /** 기타 시스템 알림 */
    SYSTEM
}
