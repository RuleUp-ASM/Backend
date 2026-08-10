package com.ruleup.ruleup_backend.notification.domain;

/** 알림 종류. enum 이름이 응답 JSON의 type 문자열이 된다. */
public enum NotificationType {
    /** 닉네임이 검수에서 거절됨 → "닉네임을 바꿔주세요" */
    NICKNAME_REJECTED,
    /** 프로필 사진이 검수에서 거절됨 → "프로필 사진을 바꿔주세요" */
    PROFILE_IMAGE_REJECTED,
    /** 챌린지 제목/설명이 심사에서 거절됨 → "제목·설명을 바꿔주세요"(대체 표시 유지) */
    CHALLENGE_NAME_REJECTED,
    /** 챌린지 대표 이미지가 심사에서 거절됨 → 이미지 삭제 + "이미지를 바꿔주세요" */
    CHALLENGE_IMAGE_REJECTED,
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
    COMMENT_CREATED,
    CHALLENGE_MEMBER_KICKED,
    OWNER_TRANSFERRED,
    /** 방장이 권한을 넘기지 않고 나가 봇방장 체제로 전환됨 → 잔류 멤버 전체에게 "방장 자리가 비었어요"(선착순 클레임 유도) */
    BOT_OWNER_ACTIVATED,
    /** 기타 시스템 알림 */
    SYSTEM;

    public NotificationClass notificationClass() {
        return switch (this) {
            case NOTICE_CREATED, COMMENT_CREATED, CHALLENGE_MEMBER_KICKED,
                    OWNER_TRANSFERRED, BOT_OWNER_ACTIVATED -> NotificationClass.ROOM;
            case CHALLENGE_NAME_REJECTED, CHALLENGE_IMAGE_REJECTED, CHALLENGE_CLOSED,
                    CHALLENGE_APPROVED, CHALLENGE_JOIN_REQUESTED, CHALLENGE_MEMBER_APPROVED,
                    CHALLENGE_MEMBER_REJECTED, FALLBACK_APPROVED, FALLBACK_REJECTED,
                    WATCHER_ROUTINE_FAILED -> NotificationClass.CHALLENGE;
            default -> NotificationClass.SYSTEM;
        };
    }
}
