-- =====================================================================
-- V4: 루틴 템플릿 카테고리를 관심 카테고리 12종으로 통일 (챌린지 생성 스펙)
--  챌린지 카테고리(12종 enum)와 루틴 카테고리(구 15종)가 별개 코드 체계라
--  추천 제외("진행 중인 카테고리")·초안 카테고리 매칭이 불가능했다 → 12종으로 통일.
--  루틴 테이블은 시드 미투입(스키마만) 상태이나, 스테이징 잔존 데이터는 아래 매핑으로 이관한다.
-- =====================================================================

-- enum → varchar 로 풀고(값은 그대로 문자열 보존) 12종 코드로 매핑
ALTER TABLE `RoutineTemplate`
    MODIFY COLUMN `category` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;

UPDATE `RoutineTemplate` SET `category` = CASE `category`
    WHEN 'WAKEUP'       THEN 'WAKE_SLEEP'
    WHEN 'MEDITATION'   THEN 'MIND'
    WHEN 'HEALTH'       THEN 'DIET_HEALTH'
    WHEN 'COOKING'      THEN 'DIET_HEALTH'
    WHEN 'WORK'         THEN 'CAREER_PRODUCTIVITY'
    WHEN 'CODING'       THEN 'CAREER_PRODUCTIVITY'
    WHEN 'ENVIRONMENT'  THEN 'HOUSEKEEPING'
    WHEN 'MUSIC'        THEN 'HOBBY'
    WHEN 'WRITING'      THEN 'HOBBY'
    WHEN 'RELATIONSHIP' THEN 'ETC'
    ELSE `category`
END;
