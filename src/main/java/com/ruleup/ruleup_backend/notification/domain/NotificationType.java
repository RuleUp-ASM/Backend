package com.ruleup.ruleup_backend.notification.domain;

/** 알림 종류. enum 이름이 응답 JSON의 type 문자열이 된다. */
public enum NotificationType {
    /** 닉네임이 검수에서 거절됨 → "닉네임을 바꿔주세요" */
    NICKNAME_REJECTED,
    /** 프로필 사진이 검수에서 거절됨 → "프로필 사진을 바꿔주세요" */
    PROFILE_IMAGE_REJECTED,
    /** 챌린지 이름이 검수에서 거절됨 → "챌린지 이름을 바꿔주세요"(1시간 수정창) */
    CHALLENGE_NAME_REJECTED,
    /** 기타 시스템 알림 */
    SYSTEM
}
