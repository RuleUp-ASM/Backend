-- =====================================================================
-- V30: 신고·차단 정합 — 방 내부 기능 5-3, 신고 접수 API 명세(2026-08-26 개편)
--
--  개편의 요지는 **접수 단계에서 판단하지 않는다**는 것이다. 접수 시점의 집계·임계값 판정이
--  전부 사라졌고, 서버는 차단 등재 · 컨텍스트 스냅샷 저장 · 전건 적재만 한다.
--
--  그래서 지워지는 것이 많다.
--   · detail(자유 텍스트)      — 유저가 성실히 적어주지 않아 판단 재료로 못 쓴다. 그 텍스트를
--                               읽던 LLM 접수 필터도 함께 폐지됐다
--   · duplicate_report          — 재신고가 구조적으로 불가능해져(접수 즉시 차단→진입점 소멸)
--                               내려줄 상태가 없다
--   · review_status             — "신고가 유효한가"를 묻던 축. V29 의 status("무엇을 했는가")로 대체
--   · report_admin_review_queue — 임계값을 넘긴 건만 큐에 넣던 구조. 이제 전건이 검토 대상이라
--                               백오피스가 reports 를 직접 읽는다
--   · report_suspensions        — 신고 남용 정지는 자동 발동이 아니라 운영자 직권이다.
--                               sanctions 의 FEATURE_SUSPENSION(feature_code=REPORT)이 대신한다
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 차단 — 두 테이블을 하나로
--
--  스펙의 user_blocks 는 (blocker_id, target_type, target_id) 복합 PK 하나다. 유저와 챌린지를
--  나눠 두면 "내가 차단한 것"을 한 번에 읽을 수 없고, 대상 종류가 늘 때마다 테이블이 는다.
-- ---------------------------------------------------------------------
CREATE TABLE `user_blocks` (
    `blocker_id`  BINARY(16) NOT NULL,
    `target_type` ENUM('USER','CHALLENGE') NOT NULL,
    `target_id`   BINARY(16) NOT NULL,
    `blocked_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`blocker_id`, `target_type`, `target_id`),
    CONSTRAINT `fk_user_blocks_blocker`
        FOREIGN KEY (`blocker_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
    -- target_id 에는 FK 를 걸지 않는다. 대상이 유저일 수도 챌린지일 수도 있는 다형 참조이고,
    -- 대상이 삭제돼도 "차단했다"는 내 설정은 남아 있어야 목록에서 사라지지 않는다.
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='개인 차단 — 신고자 본인 화면에만 적용되며 제재가 아니다';

INSERT INTO `user_blocks` (`blocker_id`, `target_type`, `target_id`, `blocked_at`)
SELECT `owner_id`, 'USER', `blocked_user_id`, `created_at` FROM `blacklist_users`;

INSERT INTO `user_blocks` (`blocker_id`, `target_type`, `target_id`, `blocked_at`)
SELECT `owner_id`, 'CHALLENGE', `blocked_challenge_id`, `created_at` FROM `blacklist_challenges`;

DROP TABLE `blacklist_users`;
DROP TABLE `blacklist_challenges`;

-- ---------------------------------------------------------------------
-- 2) 신고 — 대상을 하나의 컬럼으로 모으고 폐지분을 걷어낸다
--
--  target_type 이 USER/CHALLENGE 2갈래뿐이므로 컬럼을 둘로 나눌 이유가 없다. 나눠 두면
--  조회마다 "어느 쪽이 채워졌나"를 분기해야 하고, 둘 다 채워진 행을 막을 방법도 없다.
-- ---------------------------------------------------------------------
ALTER TABLE `reports` ADD COLUMN `target_id` BINARY(16) NULL AFTER `target_type`;

UPDATE `reports`
   SET `target_id` = CASE WHEN `target_type` = 'USER' THEN `target_user_id`
                          ELSE `target_challenge_id` END;

-- 대상이 비어 있는 행은 애초에 판단할 수 없는 기록이라 남겨둘 이유가 없다.
DELETE FROM `reports` WHERE `target_id` IS NULL;

ALTER TABLE `reports` MODIFY COLUMN `target_id` BINARY(16) NOT NULL;

-- 접수 단계 판정이 사라지면서 쓰이지 않게 된 컬럼들.
ALTER TABLE `reports`
    DROP COLUMN `target_user_id`,
    DROP COLUMN `target_challenge_id`,
    DROP COLUMN `context_type`,
    DROP COLUMN `context_id`,
    DROP COLUMN `detail`,
    DROP COLUMN `duplicate_report`,
    DROP COLUMN `review_status`;

-- 구 인덱스는 사라진 컬럼을 참조하므로 함께 정리하고 대상 단위 조회용으로 다시 만든다.
DROP INDEX `ix_reports_reporter_target` ON `reports`;
DROP INDEX `ix_reports_review` ON `reports`;
CREATE INDEX `ix_reports_target` ON `reports` (`target_type`, `target_id`, `created_at`);

-- ---------------------------------------------------------------------
-- 3) 접수 게이트·큐 제거
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `report_admin_review_queue`;
DROP TABLE IF EXISTS `report_suspensions`;
