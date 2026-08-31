-- 티어·점수 시스템 — 점수 및 티어 정책 §4 + 티어·점수 백엔드 테크 스펙.
--
-- 매너 온도(36.5·EMA·밴드) 체계를 걷어내고 정수 점수 축으로 갈아탄다. 두 체계가 병존하던
-- 상태를 여기서 끝낸다 — 지금까지는 티어를 읽는 화면과 온도를 쓰는 경로가 따로 살아 있었다.
--
-- 저장의 핵심 결정 셋:
--   ① 소수 컬럼을 두지 않는다. 사이클 상태는 정수 카운트와 정수 누계만 들고 있고,
--      반영 누계는 f(k) = ⌊(2Wk+N)/2N⌋ 로 언제든 재계산된다.
--   ② 사이클 순변동 ±20 한도의 상태를 사이클 행 안에 둔다. 한도 단위가 챌린지별 각 사이클이라
--      주차 키도 계정 단위 한도 테이블도 필요 없다.
--   ③ 원장은 raw · limited · applied 셋을 모두 남긴다. 하나라도 빠지면 감사와 재계산이 안 된다.

-- ① 점수 변동 원장을 정책 계약에 맞춘다 ----------------------------------------------
--
-- 이 테이블은 V1 베이스라인에 회계 분류(transaction_type/source_type)로 정의됐지만 쓰는 코드가
-- 한 번도 없었다. 화면이 필요로 하는 것은 "어떤 사건이었나"이고 재계산이 필요로 하는 것은
-- "원점수 / 한도 적용값 / 실반영량"이라, 축이 다른 회계 분류를 사건 축으로 교체한다.
--
-- reason 은 V31 에서 화면 표기용 7종으로 잠깐 들어갔었다. 그건 API 응답의 값이지 저장 값이
-- 아니다 — 저장은 사건 6종 + incident_type 이고, 화면 표기는 읽는 쪽이 매핑한다.
ALTER TABLE `score_transactions`
    DROP CHECK `chk_score_transactions_amount`;

ALTER TABLE `score_transactions`
    DROP COLUMN `transaction_type`,
    DROP COLUMN `source_type`,
    DROP COLUMN `source_id`,
    DROP COLUMN `description`,
    CHANGE COLUMN `amount` `raw_delta` INT NOT NULL
        COMMENT '정책상 원래 계산된 변화량. 한도 적용 전',
    ADD COLUMN `limited_delta` INT NOT NULL DEFAULT 0
        COMMENT '사이클 순변동 ±20 한도를 적용한 값' AFTER `raw_delta`,
    ADD COLUMN `applied_delta` INT NOT NULL DEFAULT 0
        COMMENT '0~2,000 범위까지 적용한 실제 반영량. 셋을 다 남겨야 감사·재계산이 된다' AFTER `limited_delta`,
    MODIFY COLUMN `balance_after` INT NOT NULL COMMENT '반영 후 누적 점수',
    ADD COLUMN `cycle_no` SMALLINT NULL
        COMMENT '사이클 회차(1-based). 사건성 감점은 한도를 거치지 않으므로 NULL' AFTER `challenge_id`,
    ADD COLUMN `incident_type` ENUM('CHEAT_DETECTED','PERMISSION_KICK','VOLUNTARY_LEAVE') NULL
        COMMENT 'CONSECUTIVE_FAILURE_KICK 은 없다 — 각 주의 루틴 점수에 이미 반영돼 감점하지 않는다'
        AFTER `cycle_no`,
    ADD COLUMN `cycle_limit_applied` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '한도로 잘렸는지 — 한도 도달률 관찰용' AFTER `applied_delta`;

-- 쓰는 코드가 없었으므로 이 테이블은 비어 있다. reason 을 NOT NULL 로 올리기 전에
-- 만에 하나 남아 있을 수 있는 행을 정리한다 — 사건 종류를 복원할 근거가 없는 행들이다.
DELETE FROM `score_transactions` WHERE `reason` IS NULL;

ALTER TABLE `score_transactions`
    MODIFY COLUMN `reason` ENUM('DAILY_SUCCESS','CONFIRMED_MISS','STREAK_BONUS','STREAK_PENALTY',
                                'INCIDENT','REVERSAL') NOT NULL
        COMMENT '사건 종류. 화면 표기(CYCLE_SUCCESS 등)는 읽는 쪽이 매핑한다';

-- 정정 재계산은 최초 영향 시점 이후 이벤트를 시간순으로 재생한다. 히스토리 조회용 DESC 인덱스로는
-- 이 경로를 못 타므로 오름차순 인덱스를 따로 둔다 — 활동이 많은 계정에서 풀스캔이 되면
-- 정정 재계산 자체가 장애가 된다.
CREATE INDEX `idx_score_recalc` ON `score_transactions` (`user_id`, `created_at`, `id`);
CREATE INDEX `idx_score_cycle` ON `score_transactions` (`challenge_id`, `cycle_no`, `user_id`);

-- ② 사이클 누적 상태 — 정수 산식의 입력이자 순변동 한도의 원본 -------------------------
--
-- cycle_id 대신 cycle_no 를 쓴다. 사이클은 테이블이 아니라 시작일로부터의 주 단위 계산이라
-- (ChallengeCycle.CYCLE_DAYS = 7) 참조할 행이 없고, 회차 번호가 곧 자연키다.
CREATE TABLE `cycle_score_states` (
    `user_id`                BINARY(16)  NOT NULL,
    `challenge_id`           BINARY(16)  NOT NULL,
    `cycle_no`               SMALLINT    NOT NULL COMMENT '챌린지 시작일 기준 주 회차(1-based)',
    `tier_snapshot`          VARCHAR(10) NOT NULL
        COMMENT '사이클 시작 시점의 실제 티어. 주중 승급·강등이 있어도 이 사이클 배점은 안 바뀐다',
    `target_count`           TINYINT     NOT NULL COMMENT '주간 목표 횟수 1~7',
    `success_count`          TINYINT     NOT NULL DEFAULT 0 COMMENT '성공 축 정수 카운트',
    `miss_count`             TINYINT     NOT NULL DEFAULT 0 COMMENT '미달 축 정수 카운트 — 성공과 별개 축',
    `settled_success_points` INT         NOT NULL DEFAULT 0 COMMENT 'f(W성공, N, success_count)',
    `settled_miss_points`    INT         NOT NULL DEFAULT 0 COMMENT 'f(W미달, N, miss_count)',
    `raw_cumulative`         INT         NOT NULL DEFAULT 0 COMMENT '원점수 누계 — 전액 누적하며 클램핑하지 않는다',
    `limited_cumulative`     INT         NOT NULL DEFAULT 0
        COMMENT '반영 누계 — applied_delta 만큼만 전진한다. target 으로 덮어쓰면 0점 계정에서 없던 점수가 지급된다',
    `cycle_result`           ENUM('SUCCESS','PARTIAL','FAILURE') NULL COMMENT '마감 전이면 NULL',
    `started_on`             DATE        NOT NULL COMMENT '사이클 시작일(KST 달력)',
    `last_judged_at`         DATETIME(3) NULL
        COMMENT '이 사이클에 접어 넣은 판정 중 가장 늦은 확정 시각. 정산 배치의 고수위 워터마크다',
    `closed_at`              DATETIME(3) NULL COMMENT 'NULL 이면 진행 중',
    `version`                BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`user_id`, `challenge_id`, `cycle_no`),
    CONSTRAINT `ck_cycle_limited_range` CHECK (`limited_cumulative` BETWEEN -20 AND 20),
    CONSTRAINT `ck_cycle_target_count` CHECK (`target_count` BETWEEN 1 AND 7),
    CONSTRAINT `fk_cycle_score_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='챌린지·사이클별 점수 누적 상태 + 순변동 ±20 한도';

-- 마감 배치는 미마감 행만 훑으므로 closed_at 이 선행이다(대부분 마감된 소수 행만 남는다).
CREATE INDEX `idx_cycle_open` ON `cycle_score_states` (`closed_at`, `challenge_id`, `cycle_no`);

-- ③ 챌린지별 연속 기록 -------------------------------------------------------------
CREATE TABLE `challenge_streaks` (
    `user_id`        BINARY(16) NOT NULL,
    `challenge_id`   BINARY(16) NOT NULL,
    `success_streak` INT        NOT NULL DEFAULT 0 COMMENT '연속 성공 — 보너스 계산',
    `failure_streak` INT        NOT NULL DEFAULT 0 COMMENT '연속 실패 — 방 내부 모듈의 경고·강퇴 트리거 입력',
    `last_cycle_no`  SMALLINT   NULL COMMENT '마지막으로 반영한 사이클 — 같은 사이클 중복 반영 방지',
    `version`        BIGINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`user_id`, `challenge_id`),
    CONSTRAINT `fk_challenge_streaks_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='챌린지별 연속 성공·실패. 사이클 판정 결과로만 움직인다';

-- 연속 실패 2·3사이클 도달자 추출 — 방 내부 모듈의 경고·강퇴 대상이다.
CREATE INDEX `idx_streak_failure` ON `challenge_streaks` (`challenge_id`, `failure_streak`);

-- ④ 정정 관계 — 원본을 지우지 않고 관계만 남긴다 ---------------------------------------
CREATE TABLE `score_corrections` (
    `id`                 BINARY(16)  NOT NULL COMMENT 'UUIDv7',
    `user_id`            BINARY(16)  NOT NULL,
    `original_event_id`  BINARY(16)  NOT NULL COMMENT '정정된 원본 판정(verificationDailyId)',
    `correction_version` INT         NOT NULL DEFAULT 1 COMMENT '정정 회차',
    `challenge_id`       BINARY(16)  NOT NULL,
    `cycle_no`           SMALLINT    NOT NULL,
    `affected_from`      DATETIME(3) NOT NULL COMMENT '최초 영향 시점 — 재계산의 시작점',
    `created_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_correction_origin` (`original_event_id`, `correction_version`),
    KEY `idx_correction_user` (`user_id`, `created_at`),
    CONSTRAINT `fk_score_corrections_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='소급 정정 이력. 같은 판정의 재정정을 UNIQUE 로 막는다';

-- 티어별 사용자 목록·티어 내 순위. display_tier 를 쓰는 이유는 입장 판정과 화면이 이 값을 보기 때문이다.
CREATE INDEX `idx_summary_tier_ranking`
    ON `user_score_summaries` (`display_tier`, `total_score` DESC, `user_id`);

-- ⑤ 매너 온도 체계 철거 ------------------------------------------------------------
--
-- 36.5 시작 · EMA 갱신 · 밴드 라벨은 전부 폐기됐다. 챌린지 게이팅도 최소 매너 온도가 아니라
-- 표시 티어(min_tier)로 하고 있으므로 온도 컬럼은 읽는 곳이 남아 있지 않다.
ALTER TABLE `challenges` DROP CHECK `ck_challenges_min_manner`;
ALTER TABLE `challenges` DROP COLUMN `min_manner_temperature`;

DROP TABLE IF EXISTS `ReputationSnapshot`;
DROP TABLE IF EXISTS `ReputationScore`;
DROP TABLE IF EXISTS `Milestone`;
