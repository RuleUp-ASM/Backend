-- 경로: src/main/resources/db/migration/V10__create_watcher_tables.sql
-- CLAUDE.md §6.3 — 감시자 도메인 신규 스키마(감시자 통지 스펙·API 기준).
-- 합법성 게이트(§5.9): 비유저 연락처는 감시자 본인이 웹에서 직접 제출 → 암호화 저장 + 생성자에겐 마스킹만.
-- 네이밍은 기존 테이블 컨벤션(camelCase) 유지.

-- ===== watcher_invitation: 생성자가 발급하는 초대(토큰·만료 7일). 공개 진입은 이 테이블로만. =====
CREATE TABLE WatcherInvitation (
    id            BINARY(16)  PRIMARY KEY,
    token         VARCHAR(64) NOT NULL,
    inviterUserId BINARY(16)  NOT NULL,                 -- 챌린지 생성자(초대 주체)
    challengeId   BINARY(16)  NOT NULL,
    status        ENUM('INVITED','CONSENTED','EXPIRED','REVOKED') NOT NULL DEFAULT 'INVITED',
    expiresAt     DATETIME(6) NOT NULL,                 -- 생성 + 7일
    createdAt     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uqWatcherInvitationToken UNIQUE (token),
    CONSTRAINT fkWatcherInvitationChallenge FOREIGN KEY (challengeId) REFERENCES Challenge(id),
    CONSTRAINT fkWatcherInvitationInviter   FOREIGN KEY (inviterUserId) REFERENCES User(id),
    KEY ixWatcherInvitationChallengeStatus (challengeId, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== watcher: 초대 1건당 1행. 수락/동의로 type·channel·연락처가 채워진다. =====
CREATE TABLE Watcher (
    id               BINARY(16)  PRIMARY KEY,
    invitationId     BINARY(16)  NOT NULL,
    challengeId      BINARY(16)  NOT NULL,
    inviterUserId    BINARY(16)  NOT NULL,
    type             ENUM('USER','NON_USER'),            -- 수락/동의 전엔 NULL
    channel          ENUM('IN_APP','SMS'),
    status           ENUM('INVITED','CONSENTED','ACTIVE','EXPIRED','REVOKED') NOT NULL DEFAULT 'INVITED',
    watcherUserId    BINARY(16),                          -- USER 타입(룰업 유저)
    contactEnc       VARBINARY(512),                      -- NON_USER 연락처(암호화). 생성자에 미노출.
    contactMasked    VARCHAR(32),                         -- 생성자 노출용 마스킹(010-****-5678)
    displayName      VARCHAR(40),                         -- 유저면 닉네임, 비유저면 NULL
    unsubscribeToken VARCHAR(64),                         -- SMS 수신거부 링크 토큰
    consentedAt      DATETIME(6),
    revokedAt        DATETIME(6),
    invitedAt        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    createdAt        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uqWatcherInvitation UNIQUE (invitationId),
    CONSTRAINT uqWatcherUnsubToken UNIQUE (unsubscribeToken),
    CONSTRAINT fkWatcherInvitation FOREIGN KEY (invitationId)  REFERENCES WatcherInvitation(id),
    CONSTRAINT fkWatcherChallenge  FOREIGN KEY (challengeId)   REFERENCES Challenge(id),
    CONSTRAINT fkWatcherInviter    FOREIGN KEY (inviterUserId) REFERENCES User(id),
    KEY ixWatcherChallengeStatus (challengeId, status),
    KEY ixWatcherInviterStatus   (inviterUserId, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== watcher_otp: 비유저 동의 1단계(SMS OTP). 본인이 웹에서 입력한 번호로만 발송. =====
CREATE TABLE WatcherOtp (
    id                BINARY(16)  PRIMARY KEY,            -- otpId
    invitationId      BINARY(16)  NOT NULL,
    phoneEnc          VARBINARY(512) NOT NULL,            -- 입력 번호(암호화) — 동의 시 watcher로 이전
    phoneHash         CHAR(64)    NOT NULL,               -- 차단 대조용 결정적 해시(sha256)
    codeHash          CHAR(64)    NOT NULL,               -- OTP 코드 해시(평문 저장 금지)
    expiresAt         DATETIME(6) NOT NULL,
    resendAvailableAt DATETIME(6) NOT NULL,
    consumedAt        DATETIME(6),
    createdAt         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fkWatcherOtpInvitation FOREIGN KEY (invitationId) REFERENCES WatcherInvitation(id),
    KEY ixWatcherOtpInvitation (invitationId, createdAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== watcher_block: 생성자–감시자 단위 30일 재초대/재등록 차단 원장(§5.9). =====
CREATE TABLE WatcherBlock (
    id            BINARY(16)  PRIMARY KEY,
    inviterUserId BINARY(16)  NOT NULL,
    subjectKey    CHAR(64)    NOT NULL,                   -- USER: sha256("U:"+userId) / NON_USER: sha256("P:"+phone)
    blockedUntil  DATETIME(6) NOT NULL,                  -- 차단 해제 시각(+30일)
    createdAt     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fkWatcherBlockInviter FOREIGN KEY (inviterUserId) REFERENCES User(id),
    KEY ixWatcherBlockLookup (inviterUserId, subjectKey, blockedUntil)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
