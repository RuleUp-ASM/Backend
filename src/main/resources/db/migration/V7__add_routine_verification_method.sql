-- ============================================================
-- V7: RoutineTemplate.verificationMethod 명시 컬럼
--   - 인증 method 라우팅을 휴리스틱 → 명시 태깅으로 전환(정확·교정 가능).
--   - 값: WAKE / SCREEN_TIME_MIN / SCREEN_TIME_MAX / GPS_PRESENCE / GPS_DISTANCE / SLEEP / PHOTO / SELF_CHECK
--   - 백필은 결정적 규칙(signalSource + name/rationale 키워드)으로 1회 산출. 애매한 행은 아래 검수 쿼리로 교정.
-- ============================================================

ALTER TABLE RoutineTemplate
    ADD COLUMN verificationMethod VARCHAR(20) NULL AFTER autoSignalSource;

UPDATE RoutineTemplate SET verificationMethod = CASE
    -- 수동 전용(자동 신호 없음)
    WHEN autoSignalSource IS NULL THEN
        CASE WHEN manualSignalSource = 'GROUP_CHECK' THEN 'SELF_CHECK' ELSE 'PHOTO' END
    -- MVP 미지원 자동(헬스커넥트·외부연동·앱기능) → 수동 폴백(Phase 2)
    WHEN autoSignalSource IN ('HC_RECORD','EXTERNAL_API','APP_FEATURE') THEN 'PHOTO'
    -- GPS 계열
    WHEN autoSignalSource = 'GEOFENCE' THEN 'GPS_PRESENCE'
    WHEN autoSignalSource = 'GPS'      THEN 'GPS_DISTANCE'
    WHEN autoSignalSource = 'ACTIVITY' THEN 'GPS_DISTANCE'
    WHEN autoSignalSource = 'SLEEP'    THEN 'SLEEP'
    -- USAGE: WAKE(첫 잠금해제) / SCREEN_TIME_MAX(제약·금지·상한) / SCREEN_TIME_MIN(사용시간 도달)
    WHEN autoSignalSource = 'USAGE' AND (
            rationale LIKE '%KEYGUARD%' OR rationale LIKE '%첫 잠금해제%' OR rationale LIKE '%첫 기상%'
         ) THEN 'WAKE'
    WHEN autoSignalSource = 'USAGE' AND (
            name LIKE '%금지%' OR name LIKE '%이하%' OR name LIKE '%안 보기%' OR name LIKE '%안보기%'
            OR name LIKE '%OFF%' OR name LIKE '%잠들기%'
            OR rationale LIKE '%상한%' OR rationale LIKE '%금지%' OR rationale LIKE '%OFF%' OR rationale LIKE '%사용 0%'
         ) THEN 'SCREEN_TIME_MAX'
    WHEN autoSignalSource = 'USAGE' THEN 'SCREEN_TIME_MIN'
    ELSE 'PHOTO'
END;

-- (검수용 — 적용 후 수동 실행해 분류 확인. 마이그레이션 자체에는 영향 없음)
-- SELECT verificationMethod, COUNT(*) FROM RoutineTemplate GROUP BY verificationMethod ORDER BY 2 DESC;
-- SELECT id, category, name, autoSignalSource, verificationMethod, rationale FROM RoutineTemplate WHERE autoSignalSource='USAGE' ORDER BY verificationMethod, id;
