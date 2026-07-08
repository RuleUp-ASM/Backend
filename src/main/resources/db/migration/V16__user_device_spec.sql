-- 경로: src/main/resources/db/migration/V16__user_device_spec.sql
-- 로그인 응답에 전체 디바이스 스펙을 되돌려주기 위해 User에 스펙 컬럼을 추가한다.
--   · 기존엔 platform/appVersionCode/appVersionName 만 저장(추천 PLATFORM 세그먼트+버전)했고
--     나머지는 수신만 하고 버렸다. 이제 osVersion·sdkInt·deviceModel·manufacturer·lowRam 도 저장한다.
--   · 로그인·가입마다 최신 1건으로 갱신(User.updateDeviceInfo). 부분 전송 시 기존값 보존이라 전부 NULL 허용.
ALTER TABLE User
    ADD COLUMN osVersion    VARCHAR(32)  NULL AFTER appVersionName,   -- OS 버전(예: "14")
    ADD COLUMN sdkInt       INT          NULL AFTER osVersion,        -- 안드로이드 SDK Int(iOS는 NULL)
    ADD COLUMN deviceModel  VARCHAR(64)  NULL AFTER sdkInt,           -- 기기 모델(예: "SM-S921N")
    ADD COLUMN manufacturer VARCHAR(64)  NULL AFTER deviceModel,      -- 제조사(예: "samsung")
    ADD COLUMN lowRam       BOOLEAN      NULL AFTER manufacturer;     -- 저사양 기기 여부
