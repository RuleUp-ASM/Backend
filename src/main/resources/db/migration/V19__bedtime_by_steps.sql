-- ======================================================================
-- 취침 루틴(1601·1602·1603·1607)을 수면 기록이 아니라 걸음으로 판정한다
--
-- 수면 기록은 워치 없이는 잘 남지 않는다. 「목표 시각부터 1시간 동안 100걸음 이하」면 잠자리에 든 것으로 본다
-- (창·상한은 서버 설정 app.verification.bedtime-window-minutes·bedtime-max-steps).
-- 수면 시간 루틴(1604·1605·1606·1608)은 그대로 수면 기록을 쓴다.
--
-- 앱은 챌린지의 requiredPermissions 로 Health Connect 수집 지표를 정한다 — READ_STEPS 가 있어야 걸음 기록을
-- 보낸다. 그래서 템플릿뿐 아니라 이미 만든 방의 스냅샷도 같이 바꾼다(V13 기상과 같은 이유). 판정 방식(SLEEP)은
-- 템플릿의 verificationMethod 로 정해지므로 신호 출처를 바꿔도 그대로다.
-- ======================================================================

UPDATE `RoutineTemplate`
   SET `description` = '목표 시각부터 1시간 동안 거의 걷지 않으면 잠든 것으로 확인해요.',
       `rationale` = '목표 시각 뒤 1시간 동안의 걸음(100걸음 이하)으로 취침을 판정'
 WHERE `id` IN (1601, 1602, 1603, 1607);

UPDATE `RoutineVerification`
   SET `autoSignalSource` = 'HC_RECORD',
       `autoRequiredPermissions` = JSON_ARRAY('android.permission.health.READ_STEPS')
 WHERE `templateId` IN (1601, 1602, 1603, 1607);

-- 자동 인증으로 만든 방 중 취침 목표(bedtime_before)가 있는 방 — 템플릿 없이 만든 방도 같은 판정기를 탄다.
-- 직접 인증으로 바꾼 방은 신호를 쓰지 않는다. 안내문은 비워 새 기준으로 다시 만들게 한다.
UPDATE `challenges`
   SET `verification_config` = JSON_SET(`verification_config`,
           '$.verificationType', 'HEALTH_CONNECT',
           '$.signalSource', 'HC_RECORD',
           '$.requiredPermissions', JSON_ARRAY('android.permission.health.READ_STEPS')),
       `verification_guide` = NULL
 WHERE JSON_TYPE(JSON_EXTRACT(`params`, '$.bedtime_before')) = 'STRING'   -- JSON null 은 목표가 없는 것
   AND JSON_UNQUOTE(JSON_EXTRACT(`verification_config`, '$.selectedMethod')) = 'AUTO';
