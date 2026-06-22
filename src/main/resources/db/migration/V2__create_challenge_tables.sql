-- 경로: src/main/resources/db/migration/V2__create_challenge_tables.sql
-- 챌린지 생성 + 인증 진행률 비정규화(인증 스펙 §4.2). V1 컨벤션 동일.

-- ===== Challenge =====
CREATE TABLE Challenge (
                           id                     BINARY(16)   PRIMARY KEY,
                           creatorId              BINARY(16)   NOT NULL,
                           title                  VARCHAR(30)  NOT NULL,
                           description            VARCHAR(200),
                           imageUrl               VARCHAR(500),
                           category               VARCHAR(20)  NOT NULL,
                           participationType      ENUM('SOLO','GROUP') NOT NULL,
                           minMannerTemperature   DECIMAL(4,1),                          -- 그룹만, 솔로는 NULL
                           repeatDays             JSON         NOT NULL,                 -- 예: ["MON","TUE"]
                           durationDays           INT          NOT NULL,
                           startDate              DATE         NOT NULL,
                           endDate                DATE         NOT NULL,                 -- 서버 파생(start + duration - 1)
                           templateId             BIGINT UNSIGNED,                       -- 매칭 루틴(직접입력이면 NULL). FK 없이 앱 검증.
                           verificationConfig     JSON         NOT NULL,                 -- 인증 판정 파라미터(§4.3). 생성 시 채움.
                           params                 JSON         NOT NULL,                 -- 목표값. 예: {"distance_km":5}
                           penaltyConfig          JSON         NOT NULL,
                           rewardConfig           JSON         NOT NULL,
                           anonymity              ENUM('REAL','ANONYMOUS') NOT NULL DEFAULT 'REAL',
                           status                 ENUM('DRAFT','RECRUITING','ACTIVE','ENDED') NOT NULL DEFAULT 'RECRUITING',
                           aiAssisted             TINYINT(1)   NOT NULL DEFAULT 0,
                           participantCount       INT          NOT NULL DEFAULT 0,
                           createdAt              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                           updatedAt              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                           deletedAt              DATETIME(6),

                           CONSTRAINT fkChallengeCreator FOREIGN KEY (creatorId) REFERENCES User(id),
                           CONSTRAINT ckChallengeDuration  CHECK (durationDays >= 1),
                           CONSTRAINT ckChallengeMinManner CHECK (minMannerTemperature IS NULL OR minMannerTemperature >= 0.0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== ChallengeMember (Challenge 1:N, User 1:N) + 진행률 비정규화(§4.2) =====
CREATE TABLE ChallengeMember (
                                 id            BINARY(16)   PRIMARY KEY,
                                 challengeId   BINARY(16)   NOT NULL,
                                 userId        BINARY(16)   NOT NULL,
                                 role          ENUM('OWNER','MEMBER') NOT NULL DEFAULT 'MEMBER',
                                 status        ENUM('PENDING','ACTIVE','LEFT','REMOVED') NOT NULL,
                                 joinedAt      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- 진행률 비정규화(30분마다 갱신 + 잦은 조회). sync·확정 배치 시점에 정합성 갱신.
                                 scheduleType      ENUM('FIXED_DAYS','FREQUENCY') NOT NULL DEFAULT 'FIXED_DAYS',
                                 targetDays        INT NOT NULL DEFAULT 0,           -- 전체 대상일(빈도형: 필요 횟수 ΣN)
                                 successDays       INT NOT NULL DEFAULT 0,
                                 failDays          INT NOT NULL DEFAULT 0,
                                 progressRate      DECIMAL(5,2) NOT NULL DEFAULT 0,  -- 진행률(%)
                                 todayStatus       ENUM('SUCCESS','PENDING','FAILED','NOT_TARGET','NOT_REQUIRED'),
                                 lastSyncedAt      DATETIME(6),
    -- 빈도형(FREQUENCY) 전용 주기 카운터
                                 periodUnit        ENUM('WEEK','MONTH'),
                                 periodTarget      INT,                              -- 주기당 N
                                 curPeriodStart    DATE,
                                 curPeriodEnd      DATE,
                                 curPeriodCompleted INT,
                                 periodsTotal      INT,
                                 periodsMet        INT,

                                 CONSTRAINT fkMemberChallenge FOREIGN KEY (challengeId) REFERENCES Challenge(id),
                                 CONSTRAINT fkMemberUser      FOREIGN KEY (userId)      REFERENCES User(id),
    -- 한 챌린지에 한 사용자 1회 멤버십(재참여는 status 갱신으로 처리)
                                 CONSTRAINT uqMember UNIQUE (challengeId, userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;