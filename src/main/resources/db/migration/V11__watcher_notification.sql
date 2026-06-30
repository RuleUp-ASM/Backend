-- 경로: src/main/resources/db/migration/V11__watcher_notification.sql
-- 감시자 실패 통지 큐 (CLAUDE.md §9/§8.2/§11.4). 인증 실패 확정 시 적재 → 스윕이 발송.
-- SQS 대체(MVP): 이 테이블이 큐. 야간 디퍼(22~08시→08:00)는 scheduledAt 으로 표현.

CREATE TABLE WatcherNotification (
    id            BINARY(16)  PRIMARY KEY,
    watcherId     BINARY(16)  NOT NULL,
    challengeId   BINARY(16)  NOT NULL,
    failedUserId  BINARY(16)  NOT NULL,                 -- 실패한 감시 대상(=inviter)
    targetDate    DATE        NOT NULL,
    channel       ENUM('IN_APP','SMS') NOT NULL,
    status        ENUM('PENDING','SENT','SKIPPED') NOT NULL DEFAULT 'PENDING',
    scheduledAt   DATETIME(6) NOT NULL,                 -- 발송 예정(야간 디퍼면 08:00로 미룸)
    sentAt        DATETIME(6),
    createdAt     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- 실패 이벤트당 1회(§9): 감시자×챌린지×날짜 유니크로 중복 적재 차단(멱등).
    CONSTRAINT uqWatcherNotificationDay UNIQUE (watcherId, challengeId, targetDate),
    CONSTRAINT fkWatcherNotificationWatcher   FOREIGN KEY (watcherId)   REFERENCES Watcher(id),
    CONSTRAINT fkWatcherNotificationChallenge FOREIGN KEY (challengeId) REFERENCES Challenge(id),
    KEY ixWatcherNotificationDue (status, scheduledAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 인앱 감시자 통지용 Notification.type 확장(비파괴적 추가).
ALTER TABLE Notification
    MODIFY type ENUM(
        'NICKNAME_REJECTED',
        'PROFILE_IMAGE_REJECTED',
        'CHALLENGE_NAME_REJECTED',
        'CHALLENGE_CLOSED',
        'WATCHER_ROUTINE_FAILED',
        'SYSTEM'
    ) NOT NULL;
