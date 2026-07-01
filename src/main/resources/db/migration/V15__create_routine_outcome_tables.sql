-- 경로: src/main/resources/db/migration/V15__create_routine_outcome_tables.sql
-- 루틴 하루 확정 결과 이력(RoutineOutcome) — 추천 학습용 원천 데이터.
--   · 목적: "성공한 사람 루틴을 비슷한 카테고리의 실패한 사람에게 추천". 지금은 수집만.
--   · RoutineOutcomeCollector 일배치(03:30 KST)가 VerificationDaily 종결행(SUCCESS/FAILED)을 훑어 upsert.
--   · uq(challengeId,userId,targetDate)로 멤버×날짜 1행(재수집 멱등). FK 없이 raw UUID + 값 스냅샷.
--   · 카테고리/상태 인덱스는 이후 "같은 카테고리 고성공 템플릿" 집계용.
--   · 네이밍은 기존 테이블 컨벤션(camelCase) 유지.

CREATE TABLE RoutineOutcome (
    id                BINARY(16)   PRIMARY KEY,
    userId            BINARY(16)   NOT NULL,
    challengeId       BINARY(16)   NOT NULL,
    challengeMemberId BINARY(16)   NOT NULL,
    templateId        BIGINT UNSIGNED NULL,                 -- 직접입력 챌린지면 NULL
    category          VARCHAR(20)  NULL,                    -- 챌린지 카테고리 스냅샷
    targetDate        DATE         NOT NULL,
    status            ENUM('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED') NOT NULL,  -- 실제로는 SUCCESS/FAILED만 적재
    verifiedVia       ENUM('AUTO','MANUAL','MANUAL_FALLBACK') NULL,
    failureReason     VARCHAR(40)  NULL,
    confirmedAt       DATETIME(6)  NOT NULL,                 -- 확정 시각(수집 고수위 워터마크)
    createdAt         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uqRoutineOutcomeMemberDate UNIQUE (challengeId, userId, targetDate),
    KEY ixRoutineOutcomeConfirmedAt (confirmedAt),
    KEY ixRoutineOutcomeCategoryStatus (category, status),
    KEY ixRoutineOutcomeTemplateStatus (templateId, status),
    KEY ixRoutineOutcomeUserStatus (userId, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
