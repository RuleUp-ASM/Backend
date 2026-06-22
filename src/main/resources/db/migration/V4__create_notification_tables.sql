-- 경로: src/main/resources/db/migration/V4__create_notification_tables.sql
-- 변경점: 기존 V4의 users 모더레이션 컬럼(nickname_status 등)은 재작성에서 V1 User에 직접 포함됨.
--          따라서 V4는 Notification 테이블만 담당(ALTER·backfill 불필요 — 신규 DB).

-- ===== Notification (닉네임/사진 변경 요청 등 사용자 알림) =====
CREATE TABLE Notification (
                              id         BINARY(16)   PRIMARY KEY,
                              userId     BINARY(16)   NOT NULL,
                              type       ENUM('NICKNAME_REJECTED','PROFILE_IMAGE_REJECTED','SYSTEM') NOT NULL,
                              title      VARCHAR(100) NOT NULL,
                              message    VARCHAR(500) NOT NULL,
                              readAt     DATETIME(6),
                              createdAt  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                              KEY idxNotificationUserCreated (userId, createdAt),
                              CONSTRAINT fkNotificationUser FOREIGN KEY (userId) REFERENCES User(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;