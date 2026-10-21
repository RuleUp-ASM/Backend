-- ======================================================================
-- 고를 것이 없는 자동 방(기상·걸음·수면)에 셋업 전으로 갇힌 멤버를 평가 대상(READY)으로 올린다
--
-- READY 로 가는 길이 POST /setup·장소·앱 지정뿐이었다. 기상 방은 장소도 앱도 고를 게 없어
-- 앱이 POST /setup 을 건너뛰었고, 그 멤버는 PENDING_SETUP 으로 남았다 — sync 가 평가를 건너뛰고
-- 확정 배치도 행을 열지 않아 성공도 실패도 뜨지 않았다(2026-10-21 prod 기상 방).
-- 새로 만들거나 들어오는 멤버는 서버가 바로 READY 로 둔다. 이 파일은 이미 갇힌 멤버만 고친다.
--
-- 판정 방식은 VerificationConfigFactory 와 같이 정한다 — 직접 인증 선택이 먼저, 그다음 템플릿 컬럼.
-- 자동 방은 항상 템플릿에서 만들어지므로(초안의 auto = 템플릿이 자동 가능) 템플릿 없는 방은 대상이 아니다.
-- 장소(GPS_PRESENCE)·대상 앱(SCREEN_TIME_*)을 골라야 하는 방은 예전처럼 셋업을 기다린다.
-- ======================================================================

UPDATE `challenge_members` m
  JOIN `challenges` c ON c.`id` = m.`challenge_id`
  JOIN `RoutineVerification` rv ON rv.`templateId` = c.`template_id`
   SET m.`setup_status` = 'READY'
 WHERE m.`setup_status` = 'PENDING_SETUP'
   AND m.`status` = 'ACTIVE'
   AND c.`deleted_at` IS NULL
   AND JSON_UNQUOTE(JSON_EXTRACT(c.`verification_config`, '$.selectedMethod')) = 'AUTO'
   AND rv.`verificationMethod` IN ('WAKE', 'HEALTH', 'GPS_DISTANCE', 'ACTIVITY', 'SLEEP');
