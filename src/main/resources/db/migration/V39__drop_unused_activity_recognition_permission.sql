-- =====================================================================
-- V39: 쓰지 않는 ACTIVITY_RECOGNITION 권한 제거 — 클라 제보(2026-09-11)
--
--  안드로이드가 이 권한을 선언·요청·보고까지 하는데 그 권한으로 읽는 데이터가 앱에 없다고
--  알려왔다. 확인해 보니 원인은 서버다.
--
--  V9 시드의 움직임 루틴 10건(1201~1210)이 필요 권한에 걸음·거리 읽기(Health Connect)와 함께
--  ACTIVITY_RECOGNITION 을 실어 내려보낸다. 그런데 같은 행의 판정 정의는 HEALTH_CONNECT /
--  HC_RECORD / HEALTH 다 — 서버는 Health Connect 레코드(HealthReading)만 읽고, 그나마도
--  origin 이 신뢰 목록(삼성헬스·구글핏) 밖이면 거부한다(HealthEvaluator §8.2). 단말이
--  ACTIVITY_RECOGNITION 으로 직접 센 걸음이 판정에 들어갈 자리는 아예 없다.
--
--  즉 사용자는 아무 데도 쓰이지 않는 권한 다이얼로그를 하나 더 보고 있었다. 거부해도 판정에는
--  영향이 없다 — 서버는 권한 보유를 게이트로 걸지도, sync 의 permissions 를 읽지도 않는다.
--
--  같은 이유로 수면은 이미 android.permission.health.READ_SLEEP 만 쓴다(Sleep API 아님).
--  V1 베이스라인의 PHONE/ACTIVITY·PHONE/SLEEP 행들도 이 토큰을 들고 있었으나 V12 가 id<1000 을
--  전부 지웠으므로 살아있는 출처는 아래 한 곳뿐이다.
--
--  적용 후 앱을 재시작해야 RoutineCatalog 메모리 캐시가 비워진다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 카탈로그 — 앞으로 만들어지는 방
-- ---------------------------------------------------------------------
UPDATE `RoutineVerification`
SET `autoRequiredPermissions` = JSON_REMOVE(
        `autoRequiredPermissions`,
        JSON_UNQUOTE(JSON_SEARCH(`autoRequiredPermissions`, 'one', 'ACTIVITY_RECOGNITION')))
WHERE JSON_SEARCH(`autoRequiredPermissions`, 'one', 'ACTIVITY_RECOGNITION') IS NOT NULL;

-- ---------------------------------------------------------------------
-- 2) 이미 만들어진 방의 인증 스냅샷
--
--  필요 권한은 생성 시점에 challenges.verification_config 로 떠서 박히므로(신뢰 경계),
--  카탈로그만 고치면 기존 방은 계속 이 토큰을 내려보낸다. 스냅샷은 "그 방이 만들어질 때의
--  인증 방식"을 보존하려는 장치지 판정 입력이 아니고, 이 토큰은 판정에 쓰이지도 않으므로
--  여기서 같이 지운다 — 지우지 않으면 기존 방 참여자에게만 다이얼로그가 계속 남는다.
-- ---------------------------------------------------------------------
UPDATE `challenges`
SET `verification_config` = JSON_REMOVE(
        `verification_config`,
        JSON_UNQUOTE(JSON_SEARCH(`verification_config`, 'one', 'ACTIVITY_RECOGNITION',
                                 NULL, '$.requiredPermissions')))
WHERE JSON_SEARCH(`verification_config`, 'one', 'ACTIVITY_RECOGNITION',
                  NULL, '$.requiredPermissions') IS NOT NULL;
