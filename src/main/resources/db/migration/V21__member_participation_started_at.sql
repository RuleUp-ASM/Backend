-- 이번 참여의 시작 시각. joined_at 은 처음 들어온 시각이라 재입장해도 바뀌지 않는다(인기 점수는
-- challenge_join_events 를 센다). 진행 중 입장은 가입 다음 날부터 판정하므로(QA JOIN-14) 재입장한 날도
-- 판정에서 빠져야 하는데, joined_at 만 보면 재입장 당일이 판정 대상이 된다.
-- NULL 이면 joined_at 이 곧 이번 참여의 시작이다.
ALTER TABLE `challenge_members`
  ADD COLUMN `participation_started_at` datetime(6) DEFAULT NULL
    COMMENT '재입장 시각 — NULL 이면 joined_at 이 이번 참여의 시작' AFTER `joined_at`;

-- 기존 재입장 멤버 보정: 첫 가입 사건보다 늦은 가입 사건이 있으면 그 마지막 시각이 이번 참여의 시작이다.
UPDATE `challenge_members` m
  JOIN (SELECT `challenge_id`, `user_id`, MAX(`joined_at`) AS `last_join`
          FROM `challenge_join_events`
         GROUP BY `challenge_id`, `user_id`) e
    ON e.`challenge_id` = m.`challenge_id` AND e.`user_id` = m.`user_id`
   SET m.`participation_started_at` = e.`last_join`
 WHERE e.`last_join` > DATE_ADD(m.`joined_at`, INTERVAL 1 MINUTE);
