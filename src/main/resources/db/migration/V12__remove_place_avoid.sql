-- ======================================================================
-- 장소 피하기(GPS_AVOID) 유형 제거 — 템플릿 1101~1110
--
-- 「등록한 장소에 머물지 않았다」는 판정은 성립하지 않는다. 피할 장소를 사용자가 직접 등록하므로
-- 안 가는 곳을 등록하면 매일 저절로 성공하고, 휴대폰을 두고 가도 성공한다. 유형째 뺀다.
--
-- 이미 그 유형으로 만든 방은 지우지 않는다 — 참여자·기록이 붙어 있다. 대신 판정 엔진이 다시
-- 고를 수 없게 템플릿 연결을 끊고 직접 인증(SELF_CHECK)으로 바꾼다. 템플릿이 없어진 채로
-- 자동 인증으로 두면 휴리스틱이 신호원(GEOFENCE)만 보고 「장소 방문」으로 판정해, 피하던
-- 장소에 가야 성공하는 정반대의 방이 된다.
-- ======================================================================

UPDATE `challenges`
   SET `verification_config` = JSON_OBJECT(
           'selectedMethod', 'MANUAL',
           'verificationType', 'MANUAL',
           'signalSource', 'SELF_CHECK',
           'wearableReq', 'NONE',
           'requiredPermissions', JSON_ARRAY(),
           'externalService', NULL),
       `verification_type` = 'MANUAL',
       `verification_guide` = NULL,
       `template_id` = NULL
 WHERE `template_id` BETWEEN 1101 AND 1110;

-- 확정 전 초안이 지워진 템플릿을 가리키면 생성 때 자동 인증 정의를 찾지 못한다.
UPDATE `challenge_drafts` SET `template_id` = NULL WHERE `template_id` BETWEEN 1101 AND 1110;

DELETE FROM `TemplateSegmentScore` WHERE `templateId` BETWEEN 1101 AND 1110;
DELETE FROM `TemplateStats` WHERE `templateId` BETWEEN 1101 AND 1110;
DELETE FROM `RoutineVerification` WHERE `templateId` BETWEEN 1101 AND 1110;
DELETE FROM `RoutineTemplate` WHERE `id` BETWEEN 1101 AND 1110;
