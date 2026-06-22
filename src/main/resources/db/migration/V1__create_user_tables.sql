-- 경로: src/main/resources/db/migration/V1__create_user_tables.sql
-- 재작성: PascalCase 테이블 / camelCase 컬럼 / PK·FK = UUID v7 BINARY(16)
-- 컨벤션:
--   · UUID v7        → BINARY(16)  (앱에서 uuid-creator로 생성, DB default 없음)
--   · ENUM           → 컬럼 레벨 ENUM(...)
--   · 배열/맵 설정     → JSON (값 검증은 앱)
--   · 시각            → DATETIME(6) (UTC 저장), now() → CURRENT_TIMESTAMP(6)
--   · 2차 인덱스 제외(다음주 카디널리티 학습 후 V_add_indexes로 추가). UNIQUE·FULLTEXT는 유지.
--   · 개인정보(#6): 인구통계(countryCode/birthDate/gender)는 User에 보관, 탈퇴 시 앱이 익명화.
--     인증 raw 신호는 저장하지 않음(VerificationMethodResult.evidence 요약만 — V5).

-- ===== User =====
CREATE TABLE User (
                      id                  BINARY(16)      PRIMARY KEY,
                      oauthProvider       ENUM('KAKAO','NAVER','GOOGLE','APPLE') NOT NULL,
                      oauthSubject        VARCHAR(255)    NOT NULL,
                      email               VARCHAR(255),
                      nickname            VARCHAR(20)     NOT NULL,
                      nicknameStatus      ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
                      tempNickname        VARCHAR(20)     NOT NULL,                 -- 검수 전/거절 시 타인에게 보일 임시 닉네임
                      profileImageUrl     VARCHAR(500),
                      profileImageStatus  ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
                      moderationCheckedAt DATETIME(6),
                      interestCategories  JSON            NOT NULL,                 -- 코드 유효성(15종)은 앱 검증
    -- 추천용 인구통계(#7). 온보딩에서 수집, 탈퇴 시 익명화 위해 nullable.
                      countryCode         CHAR(2),                                 -- ISO 3166-1 alpha-2 (예: 'KR')
                      birthDate           DATE,                                    -- 나이는 앱에서 계산(age band 파생)
                      gender              ENUM('MALE','FEMALE'),                   -- NULL = 미입력
                      nicknameChangedAt   DATETIME(6),
                      deletedAt           DATETIME(6),                             -- 탈퇴/소프트삭제 시각
                      createdAt           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                      CONSTRAINT uqUserNickname UNIQUE (nickname),
                      CONSTRAINT uqUserOauth    UNIQUE (oauthProvider, oauthSubject),
                      CONSTRAINT ckUserProfileImageUrl CHECK (
                          profileImageUrl IS NULL OR profileImageUrl REGEXP '^https?://'
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== ReputationScore (User와 1:1, 현행 유지) =====
CREATE TABLE ReputationScore (
                                 userId             BINARY(16)   PRIMARY KEY,
                                 mannerTemperature  DECIMAL(4,1) NOT NULL DEFAULT 36.5,

                                 CONSTRAINT fkReputationUser FOREIGN KEY (userId) REFERENCES User(id),
                                 CONSTRAINT ckReputationMannerTemp CHECK (mannerTemperature >= 0.0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== RefreshToken (User와 1:N) =====
CREATE TABLE RefreshToken (
                              id          BINARY(16)   PRIMARY KEY,
                              userId      BINARY(16)   NOT NULL,
                              tokenHash   VARCHAR(64)  NOT NULL,
                              expiresAt   DATETIME(6)  NOT NULL,
                              revokedAt   DATETIME(6),
                              createdAt   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- tokenHash는 토큰 갱신마다 조회되는 핫패스 + 자연 유일 → UNIQUE로 조회 인덱스 겸함
                              CONSTRAINT uqRefreshTokenHash UNIQUE (tokenHash),
                              CONSTRAINT fkRefreshTokenUser FOREIGN KEY (userId) REFERENCES User(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== UserAgreement (User와 1:N) =====
CREATE TABLE UserAgreement (
                               id             BINARY(16)      PRIMARY KEY,
                               userId         BINARY(16)      NOT NULL,
                               agreementType  ENUM('TERMS','PRIVACY','MARKETING') NOT NULL,
                               version        VARCHAR(16)     NOT NULL,
                               agreedAt       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                               revokedAt      DATETIME(6),

                               CONSTRAINT fkUserAgreementUser FOREIGN KEY (userId) REFERENCES User(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;