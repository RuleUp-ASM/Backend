-- =====================================================================
-- V26: 계정 상태 3종 축소 + 제재 소유권 이전 — 온보딩 5-3·5-6·부록 A, 백오피스 5-3
--
--  핵심은 소유권 분리다. 지금까지 users.status 하나가 "정상/잠금/영구정지/탈퇴"를 전부
--  들고 있었는데, 이러면 제재의 기간·사유·근거를 담을 자리가 없다. 그래서
--
--    users.status  → ACTIVE / SUSPENDED / WITHDRAWN 3종만 남기고
--    sanctions     → 정지의 종류·기간·사유·근거를 단독으로 소유한다
--
--  게이트는 status 가 SUSPENDED 일 때만 sanctions 를 읽는다. 정상 사용자는 두 번째
--  조회를 하지 않으므로, 상태값을 세분화하지 않고도 제재 종류를 분리할 수 있다.
--
--  ⚠️ 조건문 하나가 전체를 무너뜨리는 지점이 있다. 활성 제재 판단과 해제 배치를
--     `ends_at IS NULL OR ends_at <= NOW()` 로 쓰면 **동결분과 영구 정지가 통째로 풀린다**
--     — 둘 다 ends_at 이 NULL 이기 때문이다. 세 경우를 구분해야 한다.
--
--       ends_at 미래 · frozen NULL   → 정상 카운트다운
--       ends_at NULL · frozen 있음   → 탈퇴 동결(시간이 흘러도 줄지 않는다)
--       ends_at NULL · frozen NULL   → 영구 정지
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 제재 이력
-- ---------------------------------------------------------------------
CREATE TABLE `sanctions` (
    `id`                   BINARY(16) NOT NULL COMMENT 'UUIDv7',
    `user_id`              BINARY(16) NOT NULL,
    -- 자동·직권을 별개 트랙으로 기록하며 합산하지 않는다. 섞으면 재범 판정이 불공정해진다.
    `track`                ENUM('AUTO','DISCRETIONARY') NOT NULL,
    -- LOCK·BAN 은 직권 전용이라 AUTO 트랙에 나타나면 가드레일 위반이다.
    `type`                 ENUM('FEATURE_SUSPENSION','LOCK','BAN') NOT NULL,
    -- 기능 정지의 대상. type 이 FEATURE_SUSPENSION 일 때만 값이 있다.
    `feature_code`         VARCHAR(30) NULL,
    `reason_code`          VARCHAR(40) NOT NULL,
    -- 운영자 입력 사유 — 고지 알림과 재검토 대응의 근거라 필수다.
    `reason_text`          VARCHAR(500) NOT NULL,
    -- 검토 근거 추적의 핵심. "검토 없이 발동된 잠금·영구 정지 0건" 감사가 이 값을 본다.
    `source`               ENUM('REPORT','ANOMALY','DIRECT') NOT NULL,
    -- report_id 또는 anomaly_signal_id. 다형적이라 FK 를 걸지 않는다.
    `source_id`            BINARY(16) NULL,
    `starts_at`            DATETIME(3) NOT NULL,
    -- 잠금은 +1개월, 영구 정지는 NULL. 동결 중에도 NULL 이 된다.
    `ends_at`              DATETIME(3) NULL,
    -- 탈퇴 시점의 잔여 초. 종료 시각이 아니라 **기간**으로 저장해야 탈퇴한 채 시간을
    -- 흘려보내 제재를 소진시키는 경로가 막힌다.
    `frozen_remaining_sec` INT NULL,
    `revoked_at`           DATETIME(3) NULL,
    -- 재검토는 제재당 1회.
    `appeal_used`          TINYINT(1) NOT NULL DEFAULT 0,
    `operator_id`          BINARY(16) NULL COMMENT '집행한 운영자 — AUTO 트랙은 NULL',
    -- NULL 이면 "고지 없이 집행된 직권 제재" 가드레일 위반이다.
    `notified_at`          DATETIME(3) NULL,
    PRIMARY KEY (`id`),
    -- 계정 게이트의 주 조회. 온보딩 모듈이 매 요청 읽는 경로라 전체 API 응답 시간에 영향을 준다.
    KEY `ix_sanctions_active` (`user_id`, `revoked_at`, `ends_at`),
    -- 마이페이지 제재 이력 — 자동·직권을 분리해 내려야 하므로 track 이 선두다.
    KEY `ix_sanctions_track` (`user_id`, `track`, `starts_at` DESC),
    -- 검토 없이 발동된 제재 감사 — 잠금·영구 정지에 선행 근거가 있는지 역조회한다.
    KEY `ix_sanctions_source` (`source`, `source_id`),
    -- 기간 경과 자동 해제 배치.
    KEY `ix_sanctions_expiry` (`revoked_at`, `ends_at`, `id`),
    -- 고지 없이 집행된 직권 제재 감사 — 상시 0에 가깝게 유지돼야 한다.
    KEY `ix_sanctions_unnotified` (`notified_at`, `starts_at`),
    CONSTRAINT `fk_sanctions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `chk_sanctions_appeal_used` CHECK (`appeal_used` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='제재 집행 이력 — 정지의 종류와 기간을 단독 소유. users.status 는 SUSPENDED 여부만 안다';

-- ---------------------------------------------------------------------
-- 2) 영구 정지 재가입 차단
--
--  users.status 만으로는 막을 수 없다. 탈퇴 1년이 지나 계정 행이 파기돼도 차단은
--  유지돼야 하므로 계정과 생명주기가 분리된 해시만 남긴다 — 그래서 user_id FK 가 없다.
--  원본 식별자는 보관하지 않는다(솔트 HMAC 이라 역산 불가).
-- ---------------------------------------------------------------------
CREATE TABLE `ban_list` (
    `id`                BINARY(16) NOT NULL COMMENT 'UUIDv7',
    `oauth_hash`        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                        COMMENT 'HMAC(salt, provider + ":" + subject)',
    `installation_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
                        COMMENT '소셜 계정을 바꿔 우회하는 경로를 막는 보조 차단',
    `sanction_id`       BINARY(16) NULL COMMENT '근거가 된 제재 — 파기 후에도 남아야 해 FK 를 걸지 않는다',
    `banned_at`         DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_ban_list_oauth` (`oauth_hash`),
    KEY `ix_ban_list_installation` (`installation_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='영구 정지 계정의 재가입 차단 — 솔트 해시만 보관';

-- ---------------------------------------------------------------------
-- 3) users.status 3종 축소
--
--  순서가 중요하다. enum 값을 빼기 전에 기존 LOCKED·BANNED 행을 sanctions 로 옮기고
--  SUSPENDED 로 전이해야 한다. 먼저 enum 을 바꾸면 해당 행이 빈 문자열로 잘려 나가
--  누가 제재 중이었는지 알 수 없게 된다.
-- ---------------------------------------------------------------------

-- 3-1. 전이 대상을 담을 수 있도록 enum 을 먼저 넓힌다(양쪽 값을 모두 허용하는 중간 상태).
ALTER TABLE `users`
    MODIFY COLUMN `status`
        ENUM('ACTIVE','LOCKED','BANNED','WITHDRAWN','SUSPENDED') NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE `users`
    MODIFY COLUMN `status_before_withdrawal`
        ENUM('ACTIVE','LOCKED','BANNED','WITHDRAWN','SUSPENDED') NULL;

-- 3-2. 기존 제재 상태를 sanctions 로 옮긴다.
--      사유 근거가 남아 있지 않으므로 source=DIRECT · reason_code=OPS_INTERFERENCE 로 적재하고
--      reason_text 에 이관분임을 명시한다. 잠금은 정책 기본값인 +1개월을 준다.
INSERT INTO `sanctions`
    (`id`, `user_id`, `track`, `type`, `feature_code`, `reason_code`, `reason_text`,
     `source`, `source_id`, `starts_at`, `ends_at`, `frozen_remaining_sec`,
     `revoked_at`, `appeal_used`, `operator_id`, `notified_at`)
SELECT UUID_TO_BIN(UUID()), u.`id`, 'DISCRETIONARY',
       CASE u.`status` WHEN 'LOCKED' THEN 'LOCK' ELSE 'BAN' END,
       NULL, 'OPS_INTERFERENCE',
       '구 users.status 기반 제재를 sanctions 로 이관한 건입니다. 상세 사유는 이관 전 기록에 없습니다.',
       'DIRECT', NULL, COALESCE(u.`updated_at`, NOW(3)),
       CASE u.`status` WHEN 'LOCKED' THEN DATE_ADD(NOW(3), INTERVAL 1 MONTH) ELSE NULL END,
       NULL, NULL, 0, NULL, NOW(3)
  FROM `users` u
 WHERE u.`status` IN ('LOCKED', 'BANNED');

-- 3-3. 영구 정지분은 밴리스트에도 남긴다.
--      해시는 애플리케이션 솔트(HMAC)로 만들어야 하므로 여기서는 넣지 않는다. 이관 시점에
--      영구 정지 계정이 있으면 배포 후 재해시 스크립트로 채운다 — 지금은 계정 행이 남아
--      users.status 로 막히므로 차단이 끊기지는 않는다.

-- 3-4. 상태를 SUSPENDED 로 전이.
UPDATE `users` SET `status` = 'SUSPENDED' WHERE `status` IN ('LOCKED', 'BANNED');
UPDATE `users` SET `status_before_withdrawal` = 'SUSPENDED'
 WHERE `status_before_withdrawal` IN ('LOCKED', 'BANNED');

-- 3-5. 이제 구 값을 뺀다.
ALTER TABLE `users`
    MODIFY COLUMN `status` ENUM('ACTIVE','SUSPENDED','WITHDRAWN') NOT NULL DEFAULT 'ACTIVE'
        COMMENT '3종. 정지의 종류·기간은 sanctions 가 소유하며 게이트는 SUSPENDED 일 때만 조회한다';
ALTER TABLE `users`
    MODIFY COLUMN `status_before_withdrawal` ENUM('ACTIVE','SUSPENDED','WITHDRAWN') NULL
        COMMENT '탈퇴 직전 상태 — 복원 시 제재가 남아 있으면 SUSPENDED 로 되돌린다';
