-- 경로: src/main/resources/db/migration/V4__add_user_moderation_and_notifications.sql
-- 목적: 가입은 그대로 통과시키고, 가입/변경 이후 LLM이 닉네임·프로필 사진을 검수해
--       문제(정치/음란/욕설 등)면 타인에게는 임시 닉네임/숨김으로 보이게 하는 상태 컬럼 추가.
--  · "확인 전/후" feature 분리 = nickname_status / profile_image_status (PENDING/APPROVED/REJECTED)
--  · temp_nickname = 검수 통과 전·거절 시 다른 사용자에게 보여줄 임시 닉네임
--  · notifications = "닉네임을 바꿔주세요" 등 사용자 알림

-- ===== users: 검수 상태 컬럼 =====
ALTER TABLE users
    ADD COLUMN nickname_status      ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING' AFTER nickname,
    ADD COLUMN temp_nickname        VARCHAR(20)                                              AFTER nickname_status,
    ADD COLUMN profile_image_status ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING' AFTER profile_image_url,
    ADD COLUMN moderation_checked_at DATETIME(6)                                             AFTER profile_image_status;

-- 기존 회원은 이미 정상 사용 중 → 승인 처리하고, id에서 임시 닉네임을 backfill
UPDATE users
SET nickname_status      = 'APPROVED',
    profile_image_status = 'APPROVED',
    temp_nickname        = CONCAT('user_', SUBSTRING(REPLACE(id, '-', ''), 1, 6))
WHERE temp_nickname IS NULL;

-- backfill 끝났으니 임시 닉네임은 필수로 고정 (신규 가입은 앱에서 항상 채움)
ALTER TABLE users
    MODIFY COLUMN temp_nickname VARCHAR(20) NOT NULL;

-- 검수 보류(PENDING)된 사용자를 주기적으로 재검수하려 할 때 스캔용
CREATE INDEX idx_users_nickname_status ON users (nickname_status);
CREATE INDEX idx_users_profile_status  ON users (profile_image_status);

-- ===== notifications: 사용자 알림 (닉네임/사진 변경 요청 등) =====
CREATE TABLE notifications (
                               id         CHAR(36)     PRIMARY KEY,
                               user_id    CHAR(36)     NOT NULL,
                               type       ENUM('NICKNAME_REJECTED','PROFILE_IMAGE_REJECTED','SYSTEM') NOT NULL,
                               title      VARCHAR(100) NOT NULL,
                               message    VARCHAR(500) NOT NULL,
                               read_at    DATETIME(6),
                               created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                               CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id)
);
-- 내 알림을 최신순/안읽음 우선으로 조회
CREATE INDEX idx_notifications_user ON notifications (user_id, read_at, created_at);
