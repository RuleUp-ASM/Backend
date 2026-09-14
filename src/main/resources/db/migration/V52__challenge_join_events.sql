-- =====================================================================
-- 영속 가입 이벤트 이력 (탐색 공통 5-1 · 백엔드 6-2 · 8-3)
--
-- 인기 점수는 「최근 24시간 신규 참여자 수」로 정해지는데, 지금까지 그 수를 멤버십 행의
-- `joined_at` 하나로 셌다. 멤버십은 <b>상태</b>라 사람당 한 줄이고, 그 줄의 시각은 마지막
-- 한 번만 남는다. 그래서 두 가지가 동시에 깨졌다.
--
--   ① 재입장이 인기에 안 잡힌다 — 처음 들어온 날짜가 굳어 있으면 오늘 다시 들어와도
--      24시간 창 밖이다. 참여는 상태가 아니라 <b>사건</b>인데 사건이 기록되지 않았다.
--   ② 반대로 그 시각을 재입장마다 덮으면 「처음 들어온 날」이 사라진다. 한 줄로는 둘 중
--      하나만 가질 수 있고, 여러 번의 가입 이력은 어느 쪽으로도 복원할 수 없다.
--
-- 사건을 사건으로 적는다. 멤버십은 상태를 그대로 지키고, 가입할 때마다 여기 한 줄이 쌓인다.
-- 원천 워밍업이 인기 점수를 다시 세는 근거도 이 표다 — 멤버십만으로는 과거를 복원할 수 없다.
--
-- 되돌리지 않는다: 탈퇴해도 이미 일어난 가입 사건은 지우지 않는다(백엔드 5-2 "이미 발생한
-- 24시간 가입 이벤트는 소급 삭제하지 않는다"). 인기는 「지금 몇 명인가」가 아니라
-- 「최근에 얼마나 몰렸는가」를 보는 값이다.
-- =====================================================================

CREATE TABLE `challenge_join_events`
(
    `id`           binary(16)  NOT NULL,
    `challenge_id` binary(16)  NOT NULL,
    `user_id`      binary(16)  NOT NULL,
    `joined_at`    datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        COMMENT '이 가입 사건이 일어난 시각. 재입장하면 새 줄이 쌓인다',
    PRIMARY KEY (`id`),
    -- 인기 집계가 「그 방의 최근 24시간」을 훑는다. 방으로 좁힌 뒤 시각으로 자르는 순서다.
    KEY `ix_join_events_challenge_time` (`challenge_id`, `joined_at`),
    CONSTRAINT `fk_join_events_challenge` FOREIGN KEY (`challenge_id`)
        REFERENCES `challenges` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 이미 들어와 있는 멤버들의 첫 가입을 옮겨 심는다. 없으면 이 표가 생긴 뒤의 가입만 인기에
-- 잡혀, 배포 직후 하루 동안 모든 방의 인기가 0 으로 보인다.
INSERT INTO `challenge_join_events` (`id`, `challenge_id`, `user_id`, `joined_at`)
SELECT UNHEX(REPLACE(UUID(), '-', '')), m.challenge_id, m.user_id, m.joined_at
  FROM `challenge_members` m;
