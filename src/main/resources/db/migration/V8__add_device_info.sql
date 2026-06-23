-- ============================================================
-- V8: 기기 정보(User) + 추천 PLATFORM 세그먼트
--   - User에 클라 기기 정보(platform/appVersionCode/appVersionName) 컬럼 추가.
--     가입 시 최초 수집, 로그인마다 갱신. 추천 PLATFORM 세그먼트 축으로 사용.
--   - TemplateSegmentScore.segmentType ENUM에 'GLOBAL'·'PLATFORM' 추가
--     · PLATFORM : user.platform 기반 추천 세그먼트.
--     · GLOBAL   : 모든 유저 공통("ALL") base. 인구통계가 없어도 전체 인기도로 추천되게 함.
-- ============================================================

ALTER TABLE User
    ADD COLUMN platform            ENUM('ANDROID','IOS') NULL AFTER gender,
    ADD COLUMN appVersionCode      INT                   NULL AFTER platform,        -- 앱 버전 코드(정수)
    ADD COLUMN appVersionName      VARCHAR(32)           NULL AFTER appVersionCode,  -- 앱 버전 네임(표시용)
    ADD COLUMN deviceInfoUpdatedAt DATETIME(6)           NULL AFTER appVersionName;  -- 마지막 갱신(로그인마다)

ALTER TABLE TemplateSegmentScore
    MODIFY COLUMN segmentType ENUM('GLOBAL','COUNTRY','GENDER','AGE_BAND','PLATFORM') NOT NULL;
