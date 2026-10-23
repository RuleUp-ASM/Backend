-- ======================================================================
-- V19 되돌리기 — 취침 루틴(1601·1602·1603·1607)을 다시 수면 기록(READ_SLEEP)으로 판정한다
--
-- 2026-10-23 오후 취침을 걸음으로 바꿨으나(V19) 같은 날 원래대로 되돌린다(사용자 결정).
-- V19 는 stg·prod 에 이미 적용돼 파일을 지우거나 고치면 Flyway 검증이 기동을 막는다 → 값을 이 파일로 되돌린다.
-- 값은 V2 시드와 같다. 방 스냅샷의 verificationType 은 V19 전에도 HEALTH_CONNECT 였다.
-- ======================================================================

UPDATE `RoutineTemplate`
   SET `description` = '수면 기록의 잠든 시각으로 확인해요.',
       `rationale` = '수면 기록의 취침 시각으로 판정'
 WHERE `id` IN (1601, 1602, 1603, 1607);

UPDATE `RoutineVerification`
   SET `autoSignalSource` = 'SLEEP',
       `autoRequiredPermissions` = JSON_ARRAY('android.permission.health.READ_SLEEP')
 WHERE `templateId` IN (1601, 1602, 1603, 1607);

-- V19 가 바꾼 방과 같은 조건 — V19 이후 새로 만든 취침 방(스냅샷이 READ_STEPS)도 함께 돌아온다.
UPDATE `challenges`
   SET `verification_config` = JSON_SET(`verification_config`,
           '$.verificationType', 'HEALTH_CONNECT',
           '$.signalSource', 'SLEEP',
           '$.requiredPermissions', JSON_ARRAY('android.permission.health.READ_SLEEP')),
       `verification_guide` = NULL
 WHERE JSON_TYPE(JSON_EXTRACT(`params`, '$.bedtime_before')) = 'STRING'
   AND JSON_UNQUOTE(JSON_EXTRACT(`verification_config`, '$.selectedMethod')) = 'AUTO';
