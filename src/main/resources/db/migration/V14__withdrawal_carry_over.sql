-- 탈퇴 후 재가입 시 이전 계정의 상태·점수를 승계하기 위한 스키마 변경.
-- 탈퇴로 점수·제재를 지울 수 없게 하되(세탁 방지), 재가입 자체를 막지는 않는다.
--
-- 승계 근거는 두 가지다.
--   1) oauth_provider + oauth_subject — 같은 소셜 계정 재로그인. 이미 동작한다(탈퇴 복원).
--   2) installation_id — 소셜 계정을 바꿔도 같은 설치면 이어붙인다. 이번에 여는 경로.
--
-- 문제는 (2)를 지금 스키마로는 볼 수 없다는 것이었다. uq_users_installation_id 가 테이블 전체
-- UNIQUE 라, 탈퇴 행이 installation_id 를 계속 쥐고 있으면 그 기기에서 새 계정을 만들 수 없다.
-- 그래서 탈퇴 시 값을 지웠고, 지우니까 이력이 남지 않았다.
--
-- 해결은 이 스키마가 닉네임에 이미 쓰고 있는 방식 그대로다(V1 의 active_approved_nickname):
-- "탈퇴하지 않은 행만" UNIQUE 대상으로 삼는 생성 컬럼을 두면,
-- 탈퇴 행은 값을 유지한 채 UNIQUE 슬롯만 반납한다 → 같은 기기에 새 계정도 되고, 이력도 남는다.

ALTER TABLE `users`
    -- status 는 탈퇴 시 WITHDRAWN 으로 덮여 정지·잠금 여부가 지워진다. 승계하려면 따로 남겨야 한다.
    -- (점수·매너온도는 별도 테이블이라 탈퇴해도 살아 있으므로 복사하지 않는다)
    ADD COLUMN `status_before_withdrawal` ENUM('ACTIVE','LOCKED','BANNED') NULL
        COMMENT '탈퇴 직전 계정 상태 — 재가입 승계용. 탈퇴한 적 없으면 NULL' AFTER `status`,

    -- 탈퇴하지 않은 계정의 설치 ID만 UNIQUE 대상 (탈퇴 시 슬롯 반납, 값은 이력으로 보존)
    ADD COLUMN `active_installation_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE WHEN `status` <> 'WITHDRAWN' THEN `installation_id` ELSE NULL END
        ) STORED AFTER `installation_id`;

-- 새 UNIQUE 를 먼저 만들고(기존 데이터는 양쪽을 모두 만족) 옛 UNIQUE 를 뗀다.
CREATE UNIQUE INDEX `uq_users_active_installation_id` ON `users` (`active_installation_id`);
DROP INDEX `uq_users_installation_id` ON `users`;

-- 승계 조회: 이 설치에서 가장 최근에 탈퇴한 계정 1건.
--   WHERE installation_id = ? AND deleted_at IS NOT NULL ORDER BY deleted_at DESC LIMIT 1
CREATE INDEX `idx_users_installation_withdrawn` ON `users` (`installation_id`, `deleted_at`);

-- 주의: 이 마이그레이션 이전에 탈퇴한 계정은 installation_id 가 이미 NULL 로 지워졌다.
-- 소급 복구는 불가능하며(원본이 없다), 그 계정들은 기기 승계 대상에서 빠진다.
-- status_before_withdrawal 도 NULL 로 남아 제재 없음(ACTIVE)으로 취급된다.
