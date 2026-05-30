-- 경로: src/main/resources/db/migration/V1__create_onboarding_tables.sql

-- ===== ENUM 타입 (스펙 2.6) =====
CREATE TYPE oauth_provider AS ENUM ('KAKAO', 'NAVER', 'GOOGLE', 'APPLE');
CREATE TYPE agreement_type AS ENUM ('TERMS', 'PRIVACY', 'MARKETING');

-- ===== users =====
CREATE TABLE users (
                       id                  UUID            PRIMARY KEY,
                       oauth_provider      oauth_provider  NOT NULL,
                       oauth_subject       VARCHAR(255)    NOT NULL,
                       email               VARCHAR(255),
                       nickname            VARCHAR(20)     NOT NULL,
                       profile_image_url   VARCHAR(500),
                       interest_categories TEXT[]          NOT NULL DEFAULT '{}',
                       nickname_changed_at TIMESTAMPTZ,
                       deleted_at          TIMESTAMPTZ,
                       created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

                       CONSTRAINT uq_users_nickname UNIQUE (nickname),
                       CONSTRAINT uq_users_oauth    UNIQUE (oauth_provider, oauth_subject),
                       CONSTRAINT ck_users_interest_categories CHECK (
                           interest_categories <@ ARRAY[
                               'EXERCISE','READING','MEDITATION','HEALTH','WAKE_UP',
    'WORK','STUDY','HOBBY','COOKING','FINANCE',
    'ENVIRONMENT','RELATIONSHIP','MUSIC','WRITING','CODING'
    ]::TEXT[]
),
    CONSTRAINT ck_users_profile_image_url CHECK (
        profile_image_url IS NULL OR profile_image_url ~ '^https?://'
    )
);
CREATE INDEX idx_users_active ON users (id) WHERE deleted_at IS NULL;

-- ===== reputation_scores (User와 1:1) =====
CREATE TABLE reputation_scores (
                                   user_id            UUID         PRIMARY KEY REFERENCES users(id),
                                   manner_temperature NUMERIC(4,1) NOT NULL DEFAULT 36.5,

                                   CONSTRAINT ck_reputation_manner_temp CHECK (manner_temperature >= 0.0)
);

-- ===== refresh_tokens (User와 1:N) =====
CREATE TABLE refresh_tokens (
                                id          UUID         PRIMARY KEY,
                                user_id     UUID         NOT NULL REFERENCES users(id),
                                token_hash  VARCHAR(64)  NOT NULL,
                                expires_at  TIMESTAMPTZ  NOT NULL,
                                revoked_at  TIMESTAMPTZ,
                                created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_rt_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_rt_hash        ON refresh_tokens (token_hash);

-- ===== user_agreements (User와 1:N) =====
CREATE TABLE user_agreements (
                                 id             UUID            PRIMARY KEY,
                                 user_id        UUID            NOT NULL REFERENCES users(id),
                                 agreement_type agreement_type  NOT NULL,
                                 version        VARCHAR(16)     NOT NULL,
                                 agreed_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
                                 revoked_at     TIMESTAMPTZ
);
CREATE INDEX idx_agreements_user ON user_agreements (user_id);
CREATE INDEX idx_agreements_user_active
    ON user_agreements (user_id, agreement_type) WHERE revoked_at IS NULL;