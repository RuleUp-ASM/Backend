-- 인앱 알림 트리거 확장: 챌린지 공개/참여 신청·승인·거절 + 예비 폴백 승인·거절 알림 타입 추가.
-- (기존 값 유지 + 추가만 — 비파괴적.)
ALTER TABLE Notification
    MODIFY type ENUM(
        'NICKNAME_REJECTED',
        'PROFILE_IMAGE_REJECTED',
        'CHALLENGE_NAME_REJECTED',
        'CHALLENGE_CLOSED',
        'CHALLENGE_APPROVED',
        'CHALLENGE_JOIN_REQUESTED',
        'CHALLENGE_MEMBER_APPROVED',
        'CHALLENGE_MEMBER_REJECTED',
        'FALLBACK_APPROVED',
        'FALLBACK_REJECTED',
        'WATCHER_ROUTINE_FAILED',
        'SYSTEM'
    ) NOT NULL;
