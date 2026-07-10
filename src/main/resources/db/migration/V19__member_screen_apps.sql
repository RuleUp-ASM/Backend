-- 경로: src/main/resources/db/migration/V19__member_screen_apps.sql
-- SCREEN_TIME 측정 대상 앱(PER_MEMBER 바인딩) — my-screen-apps API.
--   · 변경은 항상 익일 00:00부터 적용(당일 교체로 인증 조작 방지) → 현재 세트 + 익일 적용 대기 세트(pending)를 분리 저장.
--   · screenApps / pendingScreenApps : [{ "packageName": "...", "appName": "..." }] JSON 배열(없으면 NULL).
--   · pendingScreenAppsEffectiveDate : 대기 세트 적용 시작일(익일). 도래하면 조회/평가 시 현재 세트로 승격.
--   · screenAppsUpdatedAt : 최근 변경 쿨다운 기준.
ALTER TABLE ChallengeMember
    ADD COLUMN screenApps                     JSON        NULL AFTER anchorUpdatedAt,
    ADD COLUMN screenAppsAppliedFrom          DATETIME(6) NULL AFTER screenApps,
    ADD COLUMN pendingScreenApps              JSON        NULL AFTER screenAppsAppliedFrom,
    ADD COLUMN pendingScreenAppsEffectiveDate DATE        NULL AFTER pendingScreenApps,
    ADD COLUMN screenAppsUpdatedAt            DATETIME(6) NULL AFTER pendingScreenAppsEffectiveDate;
