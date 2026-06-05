-- 경로: src/main/resources/db/migration/V1__create_onboarding_tables.sql
-- MySQL 8.x 기준 (PostgreSQL → MySQL 전환)
--  · UUID            → CHAR(36)            (UUIDv7은 문자열로도 시간순 정렬)
--  · ENUM 타입        → 컬럼 레벨 ENUM(...)   (MySQL엔 CREATE TYPE 없음)
--  · TEXT[]          → JSON                (MySQL엔 배열 타입 없음, 값 검증은 앱에서)
--  · TIMESTAMPTZ     → DATETIME(6)         (UTC로 저장, 앱은 Instant로 다룸)
--  · now()          → CURRENT_TIMESTAMP(6)
--  · 부분 인덱스(WHERE) → 일반 인덱스          (MySQL 미지원)

-- ===== users =====
CREATE TABLE users (
                       id                  CHAR(36)        PRIMARY KEY,
                       oauth_provider      ENUM('KAKAO','NAVER','GOOGLE','APPLE') NOT NULL,
                       oauth_subject       VARCHAR(255)    NOT NULL,
                       email               VARCHAR(255),
                       nickname            VARCHAR(20)     NOT NULL,
                       profile_image_url   VARCHAR(500),
                       interest_categories JSON            NOT NULL,
                       nickname_changed_at DATETIME(6),
                       deleted_at          DATETIME(6),
                       created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                       CONSTRAINT uq_users_nickname UNIQUE (nickname),
                       CONSTRAINT uq_users_oauth    UNIQUE (oauth_provider, oauth_subject),
    -- 관심 카테고리 코드 유효성(15종)은 앱(InterestCategory.allValid)에서 검증한다.
                       CONSTRAINT ck_users_profile_image_url CHECK (
                           profile_image_url IS NULL OR profile_image_url REGEXP '^https?://'
)
    );
-- PG의 부분 인덱스(WHERE deleted_at IS NULL)는 MySQL에 없어 일반 인덱스로 대체
CREATE INDEX idx_users_active ON users (deleted_at);

-- ===== reputation_scores (User와 1:1) =====
CREATE TABLE reputation_scores (
                                   user_id            CHAR(36)     PRIMARY KEY,
                                   manner_temperature DECIMAL(4,1) NOT NULL DEFAULT 36.5,

                                   CONSTRAINT fk_reputation_user FOREIGN KEY (user_id) REFERENCES users(id),
                                   CONSTRAINT ck_reputation_manner_temp CHECK (manner_temperature >= 0.0)
);

-- ===== refresh_tokens (User와 1:N) =====
CREATE TABLE refresh_tokens (
                                id          CHAR(36)     PRIMARY KEY,
                                user_id     CHAR(36)     NOT NULL,
                                token_hash  VARCHAR(64)  NOT NULL,
                                expires_at  DATETIME(6)  NOT NULL,
                                revoked_at  DATETIME(6),
                                created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                CONSTRAINT fk_rt_user FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX idx_rt_user ON refresh_tokens (user_id);
CREATE INDEX idx_rt_hash ON refresh_tokens (token_hash);

-- ===== user_agreements (User와 1:N) =====
CREATE TABLE user_agreements (
                                 id             CHAR(36)        PRIMARY KEY,
                                 user_id        CHAR(36)        NOT NULL,
                                 agreement_type ENUM('TERMS','PRIVACY','MARKETING') NOT NULL,
                                 version        VARCHAR(16)     NOT NULL,
                                 agreed_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                 revoked_at     DATETIME(6),

                                 CONSTRAINT fk_agreements_user FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX idx_agreements_user        ON user_agreements (user_id);
CREATE INDEX idx_agreements_user_active ON user_agreements (user_id, agreement_type);