-- ======================================================================
-- 기상 루틴(1501~1507)을 첫 잠금 해제가 아니라 걸음으로 판정한다
--
-- 잠금 해제는 누워서 알람만 끄고 다시 자도 잡히고, 일어나서 휴대폰을 안 보면 실패한다.
-- 「하루 시작 ~ 목표 시각 + 유예」 안에 걸음이 잡히면 일어난 것으로 본다(유예·걸음 수는 서버 설정).
--
-- 앱은 챌린지의 requiredPermissions 로 Health Connect 수집 지표를 정한다 — READ_STEPS 가 있어야
-- 걸음 기록을 보낸다. 그래서 템플릿뿐 아니라 이미 만든 방의 스냅샷도 같이 바꾼다. 바꾸지 않으면
-- 그 방은 판정기만 걸음을 기다리고 앱은 걸음을 보내지 않아 매일 무신호로 실패한다.
-- ======================================================================

UPDATE `RoutineTemplate`
   SET `description` = '목표 시각쯤 일어나 걸은 걸음 기록으로 기상을 확인해요.',
       `rationale` = '목표 시각 + 유예 안의 걸음으로 기상을 판정'
 WHERE `id` BETWEEN 1501 AND 1507;

UPDATE `RoutineVerification`
   SET `autoVerificationType` = 'HEALTH_CONNECT',
       `autoSignalSource` = 'HC_RECORD',
       `autoRequiredPermissions` = JSON_ARRAY('android.permission.health.READ_STEPS')
 WHERE `templateId` BETWEEN 1501 AND 1507;

-- 자동 인증으로 만든 방만 — 직접 인증으로 바꾼 방은 신호를 쓰지 않는다.
UPDATE `challenges`
   SET `verification_config` = JSON_SET(`verification_config`,
           '$.verificationType', 'HEALTH_CONNECT',
           '$.signalSource', 'HC_RECORD',
           '$.requiredPermissions', JSON_ARRAY('android.permission.health.READ_STEPS')),
       `verification_guide` = NULL
 WHERE `template_id` BETWEEN 1501 AND 1507
   AND JSON_UNQUOTE(JSON_EXTRACT(`verification_config`, '$.selectedMethod')) = 'AUTO';
