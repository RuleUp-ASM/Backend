-- =====================================================================
-- V7: 인증 v2 — 멤버 셋업/앵커/예비폴백 + 일일 verifiedVia/이의윈도우
--   테크스펙 v2 §4(셋업)·§5(PER_MEMBER 앵커)·§9(예비 폴백)·§11.6(verifiedVia)
--   엔티티(ChallengeMember, VerificationDaily) 신규 컬럼과 1:1 대응.
-- =====================================================================

-- ----- ChallengeMember: 셋업 상태 + 멤버 바인딩 앵커 + 예비 폴백 카운터 -----
ALTER TABLE ChallengeMember
    ADD COLUMN setupStatus             ENUM('PENDING_SETUP','READY') NOT NULL DEFAULT 'PENDING_SETUP'
        COMMENT '최초 진입 셋업 상태. READY 전까지 평가 스킵(§4)',
    ADD COLUMN anchors                 JSON          NULL
        COMMENT '멤버 GeoAnchor[] (PER_MEMBER). [{lat,lng,radiusM,label}] (§5)',
    ADD COLUMN anchorUpdatedAt         DATETIME(6)   NULL
        COMMENT '앵커 마지막 변경 시각(장소 수정 쿨다운 기준, §11.5)',
    ADD COLUMN fallbackUsedPeriodStart DATE          NULL
        COMMENT '예비 폴백 주1회(롤링 7일) 윈도우 시작일(§9.2)',
    ADD COLUMN fallbackUsedCount       INT           NOT NULL DEFAULT 0
        COMMENT '현재 폴백 윈도우 내 사용 횟수(§9.2)';

-- 기존 멤버(이미 활동 중)는 셋업 절차가 없던 시절 데이터 → READY로 끌어올려 평가 누락 방지.
-- (신규 가입자만 PENDING_SETUP에서 시작. 정책상 기존 멤버 재셋업을 강제하려면 이 UPDATE 제거.)
UPDATE ChallengeMember
SET setupStatus = 'READY'
WHERE status = 'ACTIVE';

-- ----- VerificationDaily: 확정 경로(verifiedVia) + 이의 윈도우 -----
ALTER TABLE VerificationDaily
    ADD COLUMN verifiedVia     ENUM('AUTO','MANUAL','MANUAL_FALLBACK') NULL
        COMMENT '확정 경로. AUTO=신호자동 / MANUAL=정규수동 / MANUAL_FALLBACK=예비폴백(§11.6)',
    ADD COLUMN disputeClosesAt DATETIME(6) NULL
        COMMENT '예비 폴백 이의 윈도우 종료 시각. 이 시각 지나 침묵=동의로 확정(§9.2)';

-- 예비 폴백 확정 배치(confirmFallbackDisputes)용 인덱스.
--   쿼리: verifiedVia='MANUAL_FALLBACK' AND verifiedAt IS NULL AND disputeClosesAt <= now
--   (verifiedVia 동등 → verifiedAt 동등(NULL) → disputeClosesAt 범위) 순서로 커버.
ALTER TABLE VerificationDaily
    ADD KEY idxVerificationDailyFallbackDispute (verifiedVia, verifiedAt, disputeClosesAt);

-- ----- (선택) 움직임 라우팅 정합화: 시드 verificationMethod 'GPS_DISTANCE' → 'HEALTH' -----
-- v2에서 움직임(거리)은 Health Connect로 평가한다. 런타임 라우팅(VerificationConfigFactory)이
-- GPS_DISTANCE/ACTIVITY 태그를 이미 HEALTH로 보내므로 아래 백필이 없어도 동작은 동일하다.
-- 시드 데이터의 표기까지 v2 의미와 일치시키고 싶을 때만 주석을 해제해 적용한다.
-- (주의: Phase 2에서 '진짜 커스텀 GPS 세션' 챌린지를 구분하려면 원래 신호원 표기를 보존하는 편이 안전.)
--
-- UPDATE RoutineVerification rv
-- SET rv.verificationMethod = 'HEALTH'
-- WHERE rv.verificationMethod = 'GPS_DISTANCE';
