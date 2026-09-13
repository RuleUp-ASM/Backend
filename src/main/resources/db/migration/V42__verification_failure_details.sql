-- =====================================================================
-- 인증 판정 불가 사유 분리 + 실패 상세 (인증 백엔드 테크스펙 4-1 · 공통 5-3)
--
-- 두 가지가 한 컬럼에 섞여 있었다.
--   · 실패 사유      — 목표에 못 미쳤다(INSUFFICIENT_DWELL · WOKE_UP_LATE …)
--   · 판정 불가 사유  — 판정할 재료가 없었다(PERMISSION_MISSING · NO_SIGNAL)
-- 스펙이 둘을 다른 층으로 못 박았다. 「권한 부족과 신호 없음을 구분」해야 유저에게
-- 무엇을 고치라고 안내할 수 있고, 「권한 미허용 지속」 처리도 이 값을 본다.
--
-- 실패 상세를 뗀 이유는 두 가지다.
--   ① 성공 행에 실패 전용 컬럼이 NULL 로 깔리는 것을 막는다(하루 6만 건 규모).
--   ② 개인정보보호법의 자동화된 결정 설명 요구(공통 5-8)가 사유 코드 + 판정 근거
--      요약 + 기준값 + 실제값을 요구하는데, 결과 테이블에 그걸 다 넣으면 비대해진다.
-- =====================================================================

ALTER TABLE `VerificationDaily`
    ADD COLUMN `gapReason` varchar(30) NULL
        COMMENT '판정 불가 사유 — PERMISSION_MISSING / NO_SIGNAL. 실패 사유와 층이 다르다'
        AFTER `failureReason`;

CREATE TABLE `verification_failure_details` (
    `verificationDailyId` binary(16)   NOT NULL COMMENT '실패한 인증 결과 — 1:0..1',
    `reasonCode`          varchar(40)  NOT NULL COMMENT '실패 사유 코드 — 자동화된 결정의 설명 근거',
    `evidenceSummary`     varchar(512) NOT NULL COMMENT '사용자에게 설명 가능한 판정 근거 요약',
    `expectedValue`       json         NULL     COMMENT '적용된 성공 조건·기준값 스냅샷',
    `actualValue`         json         NULL     COMMENT '최종 판정에 사용된 실제 값',
    `createdAt`           datetime(3)  NOT NULL,
    PRIMARY KEY (`verificationDailyId`),
    -- 사유별 분포 집계(대시보드·기준값 조정 판단)에만 쓴다. 온라인 조회는 PK 로 간다.
    KEY `idx_verification_failure_reason` (`reasonCode`),
    CONSTRAINT `fk_verification_failure_daily` FOREIGN KEY (`verificationDailyId`)
        REFERENCES `VerificationDaily` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='최종 FAILED 인증의 실패 상세 — 실패 예정은 계산 상태라 행을 만들지 않는다';
