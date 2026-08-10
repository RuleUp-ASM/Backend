-- =====================================================================
-- V11: 챌린지 탐색 운영 전환 보강
--  1) 탐색 후보/신고 제외 쿼리의 초기 인덱스
--  2) V10 기본 행 시딩 뒤 기존 ACTIVE 통계와 인기 점수 실제 백필
-- =====================================================================

CREATE INDEX `ix_challenges_explore_candidate`
    ON `challenges` (`mode`, `visibility`, `status`, `id`);

CREATE INDEX `ix_reports_explore_exclusion`
    ON `reports` (`reporter_id`, `target_type`, `target_challenge_id`);

-- 기존 현재 멤버 카운터로 완주율·유지율 Projection을 채운다.
UPDATE `challenge_stats` s
    JOIN `challenges` c ON c.`id` = s.`challenge_id`
    LEFT JOIN (
        SELECT m.`challenge_id`,
               SUM(CASE WHEN m.`done` >= 10 THEN 1 ELSE 0 END) AS qualified,
               SUM(CASE WHEN m.`done` >= 10 AND m.`success_days` >= 0.8 * m.`done`
                        THEN 1 ELSE 0 END) AS qualified_success,
               SUM(m.`done`) AS total_progress,
               SUM(CASE WHEN m.`target_days` > 0
                              AND (m.`target_days` - m.`fail_days`) < 0.8 * m.`target_days`
                        THEN 0 ELSE 1 END) AS non_failed
        FROM (
            SELECT `challenge_id`, `success_days`, `fail_days`, `target_days`,
                   (`success_days` + `fail_days`) AS done
            FROM `challenge_members`
            WHERE `status` = 'ACTIVE'
        ) m
        GROUP BY m.`challenge_id`
    ) a ON a.`challenge_id` = c.`id`
SET s.`qualified_member_count` = COALESCE(a.`qualified`, 0),
    s.`qualified_success_member_count` = COALESCE(a.`qualified_success`, 0),
    s.`completion_rate` = CASE
        WHEN c.`status` = 'UPCOMING' OR COALESCE(a.`qualified`, 0) < 5 THEN NULL
        ELSE a.`qualified_success` / a.`qualified`
    END,
    s.`total_progress_count` = COALESCE(a.`total_progress`, 0),
    s.`non_failed_member_count` = COALESCE(a.`non_failed`, 0),
    s.`retention_rate` = CASE
        WHEN c.`status` = 'UPCOMING' OR COALESCE(a.`total_progress`, 0) < 30
             OR c.`participant_count` = 0 THEN NULL
        ELSE a.`non_failed` / c.`participant_count`
    END,
    s.`updated_at` = NOW(6)
WHERE c.`status` IN ('UPCOMING', 'ACTIVE');

-- 코드 전환 직후에도 인기순이 전부 0으로 시작하지 않도록 24시간 가입을 한 번 계산한다.
UPDATE `challenge_stats` s
    JOIN `challenges` c ON c.`id` = s.`challenge_id`
    LEFT JOIN (
        SELECT `challenge_id`, COUNT(*) AS recent_joins, MAX(`joined_at`) AS last_joined_at
        FROM `challenge_members`
        WHERE `joined_at` >= DATE_SUB(NOW(6), INTERVAL 24 HOUR)
        GROUP BY `challenge_id`
    ) j ON j.`challenge_id` = c.`id`
SET s.`recent_joins_24h` = COALESCE(j.`recent_joins`, 0),
    s.`last_joined_at_24h` = j.`last_joined_at`,
    s.`popularity_updated_at` = NOW(6)
WHERE c.`mode` = 'GROUP'
  AND c.`visibility` = 'PUBLIC'
  AND c.`status` IN ('UPCOMING', 'ACTIVE')
  AND c.`deleted_at` IS NULL;
