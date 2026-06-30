-- 경로: src/main/resources/db/migration/V9__notification_challenge_types.sql
-- 챌린지 모더레이션 알림(§5.1)을 위해 Notification.type ENUM 확장.
--  - CHALLENGE_NAME_REJECTED : 이름 거절 + 1시간 수정창 안내
--  - CHALLENGE_CLOSED        : 1시간 미수정으로 영구 닫힘 안내
-- (기존 값은 그대로 두고 추가만 — 비파괴적.)

ALTER TABLE Notification
    MODIFY type ENUM(
        'NICKNAME_REJECTED',
        'PROFILE_IMAGE_REJECTED',
        'CHALLENGE_NAME_REJECTED',
        'CHALLENGE_CLOSED',
        'SYSTEM'
    ) NOT NULL;
