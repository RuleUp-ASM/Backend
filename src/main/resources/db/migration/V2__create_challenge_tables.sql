-- 경로: src/main/resources/db/migration/V2__create_challenge_tables.sql
-- 챌린지 생성 기능 (테크스펙 4. DB). V1 컨벤션 동일:
--   · UUID v7        → CHAR(36)
--   · ENUM           → 컬럼 레벨 ENUM(...)
--   · 배열/가변설정    → JSON (값 검증은 앱에서: VerificationMethod/RepeatDay.allValid 등)
--   · 시각            → DATETIME(6), 날짜만 필요한 시작/종료일은 DATE
--   · now()          → CURRENT_TIMESTAMP(6)

-- ===== challenges =====
CREATE TABLE challenges (
                            id                     CHAR(36)     PRIMARY KEY,
                            creator_id             CHAR(36)     NOT NULL,
                            title                  VARCHAR(30)  NOT NULL,
                            description            VARCHAR(200),
                            image_url              VARCHAR(500),
                            category               VARCHAR(20)  NOT NULL,
                            participation_type     ENUM('SOLO','GROUP') NOT NULL,
                            min_manner_temperature DECIMAL(4,1),                 -- 그룹만, 솔로는 NULL
                            repeat_days            JSON         NOT NULL,         -- 예: ["MON","TUE"]
                            duration_days          INT          NOT NULL,
                            start_date             DATE         NOT NULL,
                            end_date               DATE         NOT NULL,         -- 서버 파생(start + duration - 1)
                            verification_methods   JSON         NOT NULL,         -- 예: ["GPS","PHOTO"]
                            penalty_config         JSON         NOT NULL,
                            reward_config          JSON         NOT NULL,
                            anonymity              ENUM('REAL','ANONYMOUS') NOT NULL DEFAULT 'REAL',
                            status                 ENUM('DRAFT','RECRUITING','ACTIVE','ENDED') NOT NULL DEFAULT 'RECRUITING',
                            ai_assisted            TINYINT(1)   NOT NULL DEFAULT 0,
                            participant_count      INT          NOT NULL DEFAULT 0,
                            created_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                            updated_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                            deleted_at             DATETIME(6),

                            CONSTRAINT fk_challenge_creator FOREIGN KEY (creator_id) REFERENCES users(id),
                            CONSTRAINT ck_challenge_duration CHECK (duration_days >= 1),
                            CONSTRAINT ck_challenge_min_manner CHECK (min_manner_temperature IS NULL OR min_manner_temperature >= 0.0)
);
-- 탐색/필터 키는 스칼라 컬럼 인덱스(스펙 2.4). 소프트삭제는 일반 인덱스로 대체.
CREATE INDEX idx_challenges_active   ON challenges (deleted_at);
CREATE INDEX idx_challenges_category ON challenges (category);
CREATE INDEX idx_challenges_status   ON challenges (status);
CREATE INDEX idx_challenges_creator  ON challenges (creator_id);

-- ===== challenge_members (Challenge 1 : N, User 1 : N) =====
CREATE TABLE challenge_members (
                                   id           CHAR(36)     PRIMARY KEY,
                                   challenge_id CHAR(36)     NOT NULL,
                                   user_id      CHAR(36)     NOT NULL,
                                   role         ENUM('OWNER','MEMBER') NOT NULL DEFAULT 'MEMBER',
                                   status       ENUM('PENDING','ACTIVE','LEFT','REMOVED') NOT NULL,
                                   joined_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                   CONSTRAINT fk_member_challenge FOREIGN KEY (challenge_id) REFERENCES challenges(id),
                                   CONSTRAINT fk_member_user      FOREIGN KEY (user_id)      REFERENCES users(id),
    -- 한 챌린지에 한 사용자 1회 멤버십 (스펙 5 재참여: 탈퇴 후 재참여는 status 갱신으로 처리)
                                   CONSTRAINT uq_member UNIQUE (challenge_id, user_id)
);
CREATE INDEX idx_members_challenge        ON challenge_members (challenge_id);
CREATE INDEX idx_members_challenge_status ON challenge_members (challenge_id, status);