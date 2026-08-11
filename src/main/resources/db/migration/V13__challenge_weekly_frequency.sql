-- 챌린지 일정은 특정 요일 고정이 아니라 "한 주에 N회" 빈도형으로 사용한다.
-- 기존 repeat_days는 레거시 판정 호환용으로 남기고, 그 배열 길이를 최초 weekly_count로 이관한다.
ALTER TABLE `challenges`
    ADD COLUMN `weekly_count` tinyint NOT NULL DEFAULT 7
        COMMENT '주간 수행 목표 횟수(1~7), FREQUENCY 일정' AFTER `repeat_days`;

UPDATE `challenges`
SET `weekly_count` = LEAST(7, GREATEST(1, JSON_LENGTH(`repeat_days`)));

ALTER TABLE `challenges`
    ADD CONSTRAINT `ck_challenges_weekly_count` CHECK (`weekly_count` BETWEEN 1 AND 7);
