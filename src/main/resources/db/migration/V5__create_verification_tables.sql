-- 경로: src/main/resources/db/migration/V5__create_verification_tables.sql
-- 인증(자동 판정) 테이블. 인증 스펙 §4를 1:N으로 분리:
--   · VerificationDaily        = 하루 1줄(그날 챌린지 단위 최종 판정)   [부모]
--   · VerificationMethodResult = 방식별 평가 N줄(GPS/SCREEN_TIME/...)   [자식]
-- 개인정보(#6): raw 신호(좌표·usageEvents 등)는 저장하지 않고 evidence 요약(JSON)만 남김.

-- ===== VerificationDaily (부모, 하루 1줄) =====
CREATE TABLE VerificationDaily (
                                   id               BINARY(16)   PRIMARY KEY,
                                   challengeMemberId BINARY(16)  NOT NULL,
                                   challengeId      BINARY(16)   NOT NULL,                       -- 비정규화(조회 최적화)
                                   userId           BINARY(16)   NOT NULL,                       -- 비정규화
                                   targetDate       DATE         NOT NULL,                       -- KST 기준(§2.10)
                                   status           ENUM('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED') NOT NULL DEFAULT 'PENDING',
                                   method           VARCHAR(40),                                 -- 충족에 기여한 방식(들). AND이면 복수 기록(앱 직렬화)
                                   failureReason    VARCHAR(40),                                 -- 실패/미충족 사유 코드
                                   windowClosesAt   DATETIME(6),                                 -- 창 닫힘 시각(제약형·시간창)
                                   finalizeAfter    DATETIME(6),                                 -- 창 닫힘 + maxSignalLagHours = 확정 가능 시각(§2.14)
                                   verifiedAt       DATETIME(6),                                 -- 확정 시각
                                   createdAt        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                   updatedAt        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

                                   KEY idxVerificationDailyStatusFinalize (status, finalizeAfter)
                                   CONSTRAINT fkVerificationDailyMember FOREIGN KEY (challengeMemberId) REFERENCES ChallengeMember(id),
                                   CONSTRAINT uqVerificationDailyMemberDate UNIQUE (challengeMemberId, targetDate)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== VerificationMethodResult (자식, 방식별 N줄) =====
CREATE TABLE VerificationMethodResult (
                                          id                  BINARY(16)   PRIMARY KEY,
                                          verificationDailyId BINARY(16)   NOT NULL,
                                          method              VARCHAR(40)  NOT NULL,                    -- GPS_PRESENCE / GPS_DISTANCE / SCREEN_TIME / WAKE / SLEEP / PHOTO / SELF_CHECK
                                          polarity            ENUM('ACHIEVEMENT','CONSTRAINT'),         -- 도달형/제약형 (수동은 무관)
                                          supported           TINYINT(1)   NOT NULL DEFAULT 1,          -- 플랫폼/권한상 지원 여부(iOS 스크린타임 등 false)
                                          status              ENUM('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED') NOT NULL DEFAULT 'PENDING',
                                          evidence            JSON,                                     -- 요약만: {dwellMinutes,usageMinutes,distanceKm,sleepHours,firstUnlockAt,...}
                                          lastEvaluatedAt     DATETIME(6),
                                          createdAt           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                          updatedAt           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

                                          CONSTRAINT fkMethodResultDaily FOREIGN KEY (verificationDailyId) REFERENCES VerificationDaily(id),
    -- 한 daily에 같은 method 1줄
                                          CONSTRAINT uqMethodResultDailyMethod UNIQUE (verificationDailyId, method)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;