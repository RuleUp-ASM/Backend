-- 경로: src/main/resources/db/migration/V1__baseline.sql
-- RuleUp 전체 스키마 baseline — RuleUp7 데이터베이스용 리베이스라인.
--   · 회원/인증 도메인은 "DB 정리" 문서 스키마(snake_case)로 전면 전환:
--     users / user_information / user_interests / moderation_requests / refresh_tokens
--     / user_agreements / social_tokens / user_score_summaries / score_transactions
--   · 그 외 도메인 테이블은 기존 V1~V11 최종 형태를 유지하되 FK만 users(id)로 재지정.
--   · RoutineTemplate/RoutineVerification 카탈로그 시드 포함.
--   · 테이블 생성 순서 무관하도록 FOREIGN_KEY_CHECKS 를 잠깐 끈다.

SET FOREIGN_KEY_CHECKS = 0;

-- ========== (A) 회원/인증 도메인 — DB 정리 문서 스키마 (MySQL 8.x / InnoDB) ==========
-- UUID: 애플리케이션 UUIDv7 → BINARY(16). 시간: DATETIME(3) UTC.
-- 문서 대비 의도적 편차:
--   · device_info JSON 대신 구조화 컬럼(platform 등) 유지 — 추천/FlushIntervalPolicy가 타입 컬럼을 소비
--   · country_code·moderation_checked_at 등 추천/모더레이션 운영 컬럼 유지
--   · user_information.birth_date/gender 는 NULL 허용(레거시 온보딩 경로 호환) — 검증은 애플리케이션에서 강제
--   · gender ENUM 은 NON_BINARY·PREFER_NOT_TO_SAY 둘 다 보유(정책 합의 전 안전값)
--   · chk_users_active_device(활성계정 기기 필수)는 계약 전환기 동안 미적용 — 애플리케이션 검증

CREATE TABLE `users` (
  `id`                          BINARY(16) NOT NULL COMMENT '애플리케이션에서 생성한 UUIDv7',
  `oauth_provider`              ENUM('KAKAO','GOOGLE','APPLE','NAVER') NOT NULL,
  -- ACTIVE/LOCKED/BANNED 에서는 반드시 존재. 최종 파기 구현 시 WITHDRAWN 행은 NULL로 익명화 가능
  `oauth_subject`               VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  `status`                      ENUM('ACTIVE','LOCKED','BANNED','WITHDRAWN') NOT NULL DEFAULT 'ACTIVE',
  -- 현재 사용자가 신청한 닉네임 (변경 시 새 신청값)
  `nickname`                    VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  -- 다른 사용자에게 항상 노출되는 닉네임 (최초 가입 직후엔 UUID 기반 임시 8자리)
  `approved_nickname`           VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `nickname_status`             ENUM('PENDING','APPROVED','REJECTED','CONFLICT') NOT NULL DEFAULT 'PENDING',
  -- 실제 승인 닉네임이 변경된 시각 (거절 후 재신청만으로는 갱신 안 함)
  `nickname_changed_at`         DATETIME(3) NULL,
  -- 사용자가 현재 제출한 이미지 (PENDING/REJECTED 가능)
  `profile_image_url`           VARCHAR(512) NULL,
  -- 다른 사용자에게 실제 노출되는 승인 이미지 (NULL=기본 프로필)
  `approved_profile_image_url`  VARCHAR(512) NULL,
  `profile_image_status`        ENUM('NONE','PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'NONE',
  `moderation_checked_at`       DATETIME(3) NULL,
  -- 단일 활성 기기: 현재 설치·기기 정보만 저장, 새 기기 로그인 시 덮어씀
  `installation_id`             VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  `device_id`                   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  `country_code`                CHAR(2) NULL,
  `platform`                    ENUM('ANDROID','IOS') NULL,
  `app_version_code`            INT NULL,
  `app_version_name`            VARCHAR(32) NULL,
  `os_version`                  VARCHAR(32) NULL,
  `sdk_int`                     INT NULL,
  `device_model`                VARCHAR(64) NULL,
  `manufacturer`                VARCHAR(64) NULL,
  `low_ram`                     TINYINT(1) NULL,
  `device_info_updated_at`      DATETIME(3) NULL,
  `last_login_at`               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_active_at`              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '인증 API 호출 시 하루 한 번 갱신',
  `deleted_at`                  DATETIME(3) NULL,
  `created_at`                  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`                  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  -- 신청/승인 닉네임은 각각 별도 UNIQUE 인덱스다. 따라서 "A가 신청한 값 = B가 승인받은 값"
  -- 같은 교차 중복은 DB 하나로 원자적으로 막지 못한다(DB 정리 §7.3에 명시된 MVP 수용 한계).
  -- 애플리케이션이 신청 전·승인 직전 두 번 검사하고, 승인 UPDATE 충돌은 CONFLICT 로 처리한다.
  -- 닉네임 입력 시점부터 완전한 선점이 필요해지면 그때 nickname_claims 테이블을 도입한다.
  -- PENDING 신청 닉네임만 UNIQUE 대상 (REJECTED/CONFLICT/WITHDRAWN 은 점유 해제)
  `active_requested_nickname`   VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
                                GENERATED ALWAYS AS (
                                  CASE WHEN `status` <> 'WITHDRAWN' AND `nickname_status` = 'PENDING'
                                       THEN `nickname` ELSE NULL END
                                ) STORED,
  -- 탈퇴하지 않은 사용자의 승인 닉네임만 UNIQUE 대상 (탈퇴 시 타인 재사용 허용)
  `active_approved_nickname`    VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
                                GENERATED ALWAYS AS (
                                  CASE WHEN `status` <> 'WITHDRAWN' THEN `approved_nickname` ELSE NULL END
                                ) STORED,
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_users_oauth_subject` CHECK (`oauth_subject` IS NOT NULL OR `status` = 'WITHDRAWN'),
  CONSTRAINT `chk_users_withdrawal` CHECK (
      (`status` = 'WITHDRAWN' AND `deleted_at` IS NOT NULL)
      OR (`status` <> 'WITHDRAWN' AND `deleted_at` IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사용자 계정 코어';

CREATE UNIQUE INDEX `uq_users_oauth_identity` ON `users` (`oauth_provider`, `oauth_subject`);
CREATE UNIQUE INDEX `uq_users_active_requested_nickname` ON `users` (`active_requested_nickname`);
CREATE UNIQUE INDEX `uq_users_active_approved_nickname` ON `users` (`active_approved_nickname`);
CREATE UNIQUE INDEX `uq_users_installation_id` ON `users` (`installation_id`);
CREATE INDEX `idx_users_device_id` ON `users` (`device_id`, `id`);

CREATE TABLE `user_information` (
  `user_id`     BINARY(16) NOT NULL,
  `birth_date`  DATE NULL,
  -- 성별 미응답 표현은 정책 합의 전 — NON_BINARY(API 계약)·PREFER_NOT_TO_SAY(DB 문서) 모두 보유
  `gender`      ENUM('MALE','FEMALE','NON_BINARY','PREFER_NOT_TO_SAY') NULL,
  `email`       VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_user_information_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사용자 개인정보';

CREATE TABLE `user_interests` (
  `user_id`     BINARY(16) NOT NULL,
  `category`    VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`, `category`),
  CONSTRAINT `fk_user_interests_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사용자 관심 카테고리';

CREATE INDEX `idx_user_interests_category` ON `user_interests` (`category`, `user_id`);

CREATE TABLE `moderation_requests` (
  `id`              BINARY(16) NOT NULL COMMENT 'UUIDv7',
  `user_id`         BINARY(16) NOT NULL,
  `target`          ENUM('NICKNAME','PROFILE_IMAGE') NOT NULL,
  -- NICKNAME이면 신청 닉네임, PROFILE_IMAGE이면 이미지 object key/URL
  `content`         VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `status`          ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
  `reject_reason`   VARCHAR(255) NULL,
  `requested_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `decided_at`      DATETIME(3) NULL,
  -- 사용자별 target 하나에 PENDING 요청 하나만 허용 (완료되면 NULL → 이력 누적)
  `pending_target`  VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
                    GENERATED ALWAYS AS (
                      CASE WHEN `status` = 'PENDING' THEN `target` ELSE NULL END
                    ) STORED,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_moderation_requests_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_moderation_request_status` CHECK (
      (`status` = 'PENDING' AND `decided_at` IS NULL AND `reject_reason` IS NULL)
      OR (`status` = 'APPROVED' AND `decided_at` IS NOT NULL AND `reject_reason` IS NULL)
      OR (`status` = 'REJECTED' AND `decided_at` IS NOT NULL AND `reject_reason` IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='닉네임 및 프로필 이미지 심사 이력';

CREATE UNIQUE INDEX `uq_moderation_user_pending_target` ON `moderation_requests` (`user_id`, `pending_target`);
CREATE INDEX `idx_moderation_requests_queue` ON `moderation_requests` (`status`, `target`, `requested_at`, `id`);
CREATE INDEX `idx_moderation_requests_user_history` ON `moderation_requests` (`user_id`, `target`, `requested_at` DESC, `id` DESC);

CREATE TABLE `refresh_tokens` (
  `id`                 BINARY(16) NOT NULL COMMENT 'UUIDv7',
  `user_id`            BINARY(16) NOT NULL,
  -- 최초 발급부터 회전된 모든 RT가 같은 family_id를 공유
  `family_id`          BINARY(16) NOT NULL,
  -- 이전 RT의 ID (최초 발급 토큰은 NULL)
  `parent_token_id`    BINARY(16) NULL,
  -- 원문 미저장 — SHA-256 32바이트
  `token_hash`         BINARY(32) NOT NULL,
  `expires_at`         DATETIME(3) NOT NULL,
  `revoked_at`         DATETIME(3) NULL,
  -- 이미 사용/폐기된 토큰이 다시 제출된 시각 (재사용 감지)
  `reuse_detected_at`  DATETIME(3) NULL,
  `created_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_refresh_tokens_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_refresh_tokens_parent` FOREIGN KEY (`parent_token_id`) REFERENCES `refresh_tokens` (`id`) ON DELETE SET NULL,
  CONSTRAINT `chk_refresh_tokens_reuse` CHECK (`reuse_detected_at` IS NULL OR `revoked_at` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Refresh Token 회전 및 폐기 정보';

CREATE UNIQUE INDEX `uq_refresh_tokens_hash` ON `refresh_tokens` (`token_hash`);
CREATE INDEX `idx_refresh_tokens_user_active` ON `refresh_tokens` (`user_id`, `revoked_at`, `expires_at`);
CREATE INDEX `idx_refresh_tokens_family_active` ON `refresh_tokens` (`family_id`, `revoked_at`, `expires_at`);
CREATE INDEX `idx_refresh_tokens_expiry` ON `refresh_tokens` (`expires_at`, `id`);

CREATE TABLE `user_agreements` (
  `id`              BINARY(16) NOT NULL COMMENT 'UUIDv7',
  `user_id`         BINARY(16) NOT NULL,
  `agreement_type`  ENUM('TOS','PRIVACY','LOCATION','MARKETING','EVENT','NIGHT_PUSH') NOT NULL,
  -- append-only 이력: 1=동의, 0=철회. 현재 상태는 최신 행으로 조회
  `agreed`          TINYINT(1) NOT NULL,
  `version`         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_user_agreements_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_user_agreements_agreed` CHECK (`agreed` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사용자 약관 동의 및 철회 이력';

CREATE INDEX `idx_user_agreements_latest` ON `user_agreements` (`user_id`, `agreement_type`, `created_at` DESC, `id` DESC);

CREATE TABLE `social_tokens` (
  `user_id`                 BINARY(16) NOT NULL,
  `provider`                ENUM('KAKAO','GOOGLE','APPLE','NAVER') NOT NULL,
  -- nonce·auth tag 포함 애플리케이션 암호화 결과
  `access_token_enc`        VARBINARY(2048) NOT NULL,
  `refresh_token_enc`       VARBINARY(2048) NULL,
  `encryption_key_version`  SMALLINT UNSIGNED NOT NULL,
  `expires_at`              DATETIME(3) NULL,
  `created_at`              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`, `provider`),
  CONSTRAINT `fk_social_tokens_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='외부 소셜 제공자 토큰';

CREATE INDEX `idx_social_tokens_expiry` ON `social_tokens` (`provider`, `expires_at`, `user_id`);

CREATE TABLE `user_score_summaries` (
  `user_id`           BINARY(16) NOT NULL,
  `total_score`       BIGINT NOT NULL DEFAULT 0,
  -- 점수만으로 계산한 실제 티어
  `actual_tier`       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNRANKED',
  -- 강등 유예 등 정책 적용 후 표시 티어
  `display_tier`      VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNRANKED',
  `tier_grace_until`  DATETIME(3) NULL,
  -- 점수 동시 업데이트 낙관적 락
  `version`           BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `created_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_user_score_summaries_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사용자 현재 점수 및 티어';

CREATE INDEX `idx_user_score_summaries_ranking` ON `user_score_summaries` (`total_score` DESC, `user_id`);
CREATE INDEX `idx_user_score_summaries_tier_ranking` ON `user_score_summaries` (`display_tier`, `total_score` DESC, `user_id`);

CREATE TABLE `score_transactions` (
  `id`                BINARY(16) NOT NULL COMMENT 'UUIDv7',
  `user_id`           BINARY(16) NOT NULL,
  -- 양수=증가, 음수=감소
  `amount`            BIGINT NOT NULL,
  -- 적용 이후 총점 (user_score_summaries 잠근 뒤 계산)
  `balance_after`     BIGINT NOT NULL,
  `transaction_type`  ENUM('CHALLENGE_SUCCESS','CHALLENGE_FAILURE','APPEAL_ADJUSTMENT','ADMIN_ADJUSTMENT','REVERSAL') NOT NULL,
  `source_type`       ENUM('CHALLENGE_CYCLE','VERIFICATION','APPEAL','ADMIN') NOT NULL,
  -- 다형적 참조라 FK 미적용 (challenge_cycle_id / verification_id / appeal_id 등)
  `source_id`         BINARY(16) NULL,
  `reversal_of_id`    BINARY(16) NULL,
  -- 같은 이벤트 중복 반영 방지 (예: challenge-cycle:{cycleId}:user:{userId}:success)
  `idempotency_key`   VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `description`       VARCHAR(255) NULL,
  `created_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_score_transactions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_score_transactions_reversal` FOREIGN KEY (`reversal_of_id`) REFERENCES `score_transactions` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_score_transactions_amount` CHECK (`amount` <> 0),
  CONSTRAINT `chk_score_transactions_self_reversal` CHECK (`reversal_of_id` IS NULL OR `reversal_of_id` <> `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사용자 점수 변경 원장';

CREATE UNIQUE INDEX `uq_score_transactions_idempotency` ON `score_transactions` (`idempotency_key`);
CREATE UNIQUE INDEX `uq_score_transactions_reversal` ON `score_transactions` (`reversal_of_id`);
CREATE INDEX `idx_score_transactions_user_history` ON `score_transactions` (`user_id`, `created_at` DESC, `id` DESC);
CREATE INDEX `idx_score_transactions_source` ON `score_transactions` (`source_type`, `source_id`, `user_id`, `created_at`);

-- ========== (B) 기타 도메인 — 기존 최종 스키마 (FK만 users로 재지정) ==========

CREATE TABLE `Challenge` (
  `id` binary(16) NOT NULL,
  `creatorId` binary(16) NOT NULL,
  `title` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `imageUrl` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `participationType` enum('SOLO','GROUP') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `minMannerTemperature` decimal(4,1) DEFAULT NULL,
  `maxParticipants` int DEFAULT NULL,
  `repeatDays` json NOT NULL,
  `durationDays` int NOT NULL,
  `startDate` date NOT NULL,
  `endDate` date NOT NULL,
  `templateId` bigint unsigned DEFAULT NULL,
  `verificationConfig` json NOT NULL,
  `params` json NOT NULL,
  `penaltyConfig` json NOT NULL,
  `rewardConfig` json NOT NULL,
  `anonymity` enum('REAL','ANONYMOUS') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'REAL',
  `status` enum('UPCOMING','ACTIVE','COMPLETED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UPCOMING',
  `moderationStatus` enum('NONE','PENDING_REVIEW','APPROVED','REJECTED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NONE',
  `moderationDecidedAt` datetime(6) DEFAULT NULL,
  `fixDeadline` datetime(6) DEFAULT NULL,
  `aiAssisted` tinyint(1) NOT NULL DEFAULT '0',
  `participantCount` int NOT NULL DEFAULT '0',
  `trendingScore` double NOT NULL DEFAULT '0',
  `failCount` int NOT NULL DEFAULT '0',
  `verificationType` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `deletedAt` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fkChallengeCreator` (`creatorId`),
  KEY `idx_challenge_explore` (`deletedAt`,`moderationStatus`,`status`,`endDate`),
  KEY `idx_challenge_template` (`templateId`),
  CONSTRAINT `fkChallengeCreator` FOREIGN KEY (`creatorId`) REFERENCES `users` (`id`),
  CONSTRAINT `ckChallengeDuration` CHECK ((`durationDays` >= 1)),
  CONSTRAINT `ckChallengeMinManner` CHECK (((`minMannerTemperature` is null) or (`minMannerTemperature` >= 0.0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `ChallengeDelegation` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `requesterId` binary(16) NOT NULL,
  `targetUserId` binary(16) NOT NULL,
  `status` enum('PENDING','ACCEPTED','REJECTED','CANCELED','EXPIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `expiresAt` datetime(6) NOT NULL,
  `resolvedAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixDelegationChallengeStatus` (`challengeId`,`status`),
  KEY `ixDelegationTargetStatus` (`targetUserId`,`status`),
  KEY `fkDelegationRequester` (`requesterId`),
  CONSTRAINT `fkDelegationChallenge` FOREIGN KEY (`challengeId`) REFERENCES `Challenge` (`id`),
  CONSTRAINT `fkDelegationRequester` FOREIGN KEY (`requesterId`) REFERENCES `users` (`id`),
  CONSTRAINT `fkDelegationTarget` FOREIGN KEY (`targetUserId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `ChallengeMember` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `role` enum('OWNER','MANAGER','MEMBER') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MEMBER',
  `status` enum('PENDING','ACTIVE','LEFT','REMOVED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `joinedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `scheduleType` enum('FIXED_DAYS','FREQUENCY') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'FIXED_DAYS',
  `targetDays` int NOT NULL DEFAULT '0',
  `successDays` int NOT NULL DEFAULT '0',
  `failDays` int NOT NULL DEFAULT '0',
  `progressRate` decimal(5,2) NOT NULL DEFAULT '0.00',
  `todayStatus` enum('SUCCESS','PENDING','FAILED_PROVISIONAL','FAILED','NOT_TARGET','NOT_REQUIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lastSyncedAt` datetime(6) DEFAULT NULL,
  `periodUnit` enum('WEEK','MONTH') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `periodTarget` int DEFAULT NULL,
  `curPeriodStart` date DEFAULT NULL,
  `curPeriodEnd` date DEFAULT NULL,
  `curPeriodCompleted` int DEFAULT NULL,
  `setupStatus` enum('PENDING_SETUP','READY') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING_SETUP' COMMENT 'ìµœì´ˆ ì§„ìž… ì…‹ì—… ìƒíƒœ. READY ì „ê¹Œì§€ í‰ê°€ ìŠ¤í‚µ(Â§4)',
  `anchors` json DEFAULT NULL COMMENT 'ë©¤ë²„ GeoAnchor[] (PER_MEMBER). [{lat,lng,radiusM,label}] (Â§5)',
  `anchorUpdatedAt` datetime(6) DEFAULT NULL COMMENT 'ì•µì»¤ ë§ˆì§€ë§‰ ë³€ê²½ ì‹œê°(ìž¥ì†Œ ìˆ˜ì • ì¿¨ë‹¤ìš´ ê¸°ì¤€, Â§11.5)',
  `screenApps` json DEFAULT NULL,
  `screenAppsAppliedFrom` datetime(6) DEFAULT NULL,
  `pendingScreenApps` json DEFAULT NULL,
  `pendingScreenAppsEffectiveDate` date DEFAULT NULL,
  `screenAppsUpdatedAt` datetime(6) DEFAULT NULL,
  `fallbackUsedPeriodStart` date DEFAULT NULL COMMENT 'ì˜ˆë¹„ í´ë°± ì£¼1íšŒ(ë¡¤ë§ 7ì¼) ìœˆë„ìš° ì‹œìž‘ì¼(Â§9.2)',
  `fallbackUsedCount` int NOT NULL DEFAULT '0' COMMENT 'í˜„ìž¬ í´ë°± ìœˆë„ìš° ë‚´ ì‚¬ìš© íšŸìˆ˜(Â§9.2)',
  `ghostPushedAt` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqMember` (`challengeId`,`userId`),
  KEY `ixMemberUserStatus` (`userId`,`status`),
  CONSTRAINT `fkMemberChallenge` FOREIGN KEY (`challengeId`) REFERENCES `Challenge` (`id`),
  CONSTRAINT `fkMemberUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `DeviceToken` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `token` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `platform` enum('ANDROID','IOS') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ANDROID',
  `lastSeenAt` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqDeviceToken` (`token`),
  KEY `ixDeviceTokenUser` (`userId`),
  CONSTRAINT `fkDeviceTokenUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `InvitationSignup` (
  `id` binary(16) NOT NULL,
  `inviterUserId` binary(16) NOT NULL,
  `inviteeUserId` binary(16) NOT NULL,
  `occurredAt` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqInvitationSignupInvitee` (`inviteeUserId`),
  KEY `ixInvitationSignupInviter` (`inviterUserId`,`occurredAt`),
  CONSTRAINT `fkInvitationSignupInvitee` FOREIGN KEY (`inviteeUserId`) REFERENCES `users` (`id`),
  CONSTRAINT `fkInvitationSignupInviter` FOREIGN KEY (`inviterUserId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `InviteCode` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqInviteCodeUser` (`userId`),
  UNIQUE KEY `uqInviteCodeCode` (`code`),
  CONSTRAINT `fkInviteCodeUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `Milestone` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `type` enum('TIER_REACHED','STREAK','FIRST_COMPLETION','SIGNUP') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `dedupKey` varchar(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `label` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `achievedAt` date NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqMilestoneUserTypeKey` (`userId`,`type`,`dedupKey`),
  KEY `ixMilestoneUserAchieved` (`userId`,`achievedAt`),
  CONSTRAINT `fkMilestoneUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `Notice` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `authorId` binary(16) NOT NULL,
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `pinned` tinyint(1) NOT NULL DEFAULT '0',
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `fkNoticeAuthor` (`authorId`),
  KEY `ixNoticeChallengePinned` (`challengeId`,`pinned`,`createdAt`),
  CONSTRAINT `fkNoticeAuthor` FOREIGN KEY (`authorId`) REFERENCES `users` (`id`),
  CONSTRAINT `fkNoticeChallenge` FOREIGN KEY (`challengeId`) REFERENCES `Challenge` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `NoticeRead` (
  `id` binary(16) NOT NULL,
  `noticeId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `readAt` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqNoticeReadNoticeUser` (`noticeId`,`userId`),
  KEY `ixNoticeReadChallengeUser` (`challengeId`,`userId`),
  KEY `fkNoticeReadUser` (`userId`),
  CONSTRAINT `fkNoticeReadNotice` FOREIGN KEY (`noticeId`) REFERENCES `Notice` (`id`),
  CONSTRAINT `fkNoticeReadUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `Notification` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `type` enum('NICKNAME_REJECTED','PROFILE_IMAGE_REJECTED','CHALLENGE_NAME_REJECTED','CHALLENGE_CLOSED','CHALLENGE_APPROVED','CHALLENGE_JOIN_REQUESTED','CHALLENGE_MEMBER_APPROVED','CHALLENGE_MEMBER_REJECTED','FALLBACK_APPROVED','FALLBACK_REJECTED','WATCHER_ROUTINE_FAILED','NOTICE_CREATED','SYSTEM') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `readAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idxNotificationUserCreated` (`userId`,`createdAt`),
  CONSTRAINT `fkNotificationUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `Objection` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `challengeMemberId` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `targetDate` date NOT NULL,
  `type` enum('FAILURE') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `imageUrl` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('PENDING','APPROVED','REJECTED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `deadline` datetime(6) NOT NULL,
  `decidedBy` binary(16) DEFAULT NULL,
  `decidedAt` datetime(6) DEFAULT NULL,
  `decisionReason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqObjectionMemberDate` (`challengeMemberId`,`targetDate`),
  KEY `ixObjectionChallengeStatus` (`challengeId`,`status`),
  KEY `fkObjectionUser` (`userId`),
  CONSTRAINT `fkObjectionChallenge` FOREIGN KEY (`challengeId`) REFERENCES `Challenge` (`id`),
  CONSTRAINT `fkObjectionMember` FOREIGN KEY (`challengeMemberId`) REFERENCES `ChallengeMember` (`id`),
  CONSTRAINT `fkObjectionUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `PushOutbox` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `targetDate` date NOT NULL,
  `type` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `signalType` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('PENDING','SENT','SKIPPED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `scheduledAt` datetime(6) NOT NULL,
  `sentAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqPushOutbox` (`userId`,`challengeId`,`targetDate`,`type`),
  KEY `ixPushOutboxDue` (`status`,`scheduledAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `ReputationScore` (
  `userId` binary(16) NOT NULL,
  `mannerTemperature` decimal(5,2) NOT NULL DEFAULT '36.50',
  `volumeIndex` decimal(7,4) NOT NULL DEFAULT '0.0000',
  `tenureBonus` decimal(6,4) NOT NULL DEFAULT '0.0000',
  `qualifyingDays` int NOT NULL DEFAULT '0',
  `lastQualifyingDate` date DEFAULT NULL,
  `lastCalculatedDate` date DEFAULT NULL,
  `peakTemperature` decimal(5,2) DEFAULT NULL,
  `peakAchievedAt` date DEFAULT NULL,
  PRIMARY KEY (`userId`),
  KEY `ixReputationCalcDate` (`lastCalculatedDate`),
  CONSTRAINT `fkReputationUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`),
  CONSTRAINT `ckReputationMannerTemp` CHECK ((`mannerTemperature` >= 0.0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `ReputationSnapshot` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `snapshotDate` date NOT NULL,
  `temperature` decimal(5,2) NOT NULL,
  `delta` decimal(6,2) NOT NULL DEFAULT '0.00',
  `label` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqReputationSnapshotUserDate` (`userId`,`snapshotDate`),
  KEY `ixReputationSnapshotUserDate` (`userId`,`snapshotDate`),
  CONSTRAINT `fkReputationSnapshotUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `RoomActivityLog` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `actorId` binary(16) DEFAULT NULL,
  `entityType` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `entityId` binary(16) DEFAULT NULL,
  `action` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixRoomLogChallengeCreated` (`challengeId`,`createdAt`),
  KEY `ixRoomLogEntity` (`entityType`,`entityId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `RoutineOutcome` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `challengeMemberId` binary(16) NOT NULL,
  `templateId` bigint unsigned DEFAULT NULL,
  `category` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `targetDate` date NOT NULL,
  `status` enum('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `verifiedVia` enum('AUTO','MANUAL','MANUAL_FALLBACK') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `failureReason` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `confirmedAt` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqRoutineOutcomeMemberDate` (`challengeId`,`userId`,`targetDate`),
  KEY `ixRoutineOutcomeConfirmedAt` (`confirmedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `RoutineTemplate` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category` enum('EXERCISE','READING','MEDITATION','HEALTH','WAKEUP','WORK','STUDY','HOBBY','COOKING','FINANCE','ENVIRONMENT','RELATIONSHIP','MUSIC','WRITING','CODING') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `paramSchema` json DEFAULT NULL,
  `rationale` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  FULLTEXT KEY `ftxNameDesc` (`name`,`description`) /*!50100 WITH PARSER `ngram` */ 
) ENGINE=InnoDB AUTO_INCREMENT=106 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `RoutineVerification` (
  `templateId` bigint unsigned NOT NULL,
  `autoVerificationType` enum('PHONE','HEALTH_CONNECT','EXTERNAL') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoSignalSource` enum('GEOFENCE','GPS','ACTIVITY','SLEEP','USAGE','APP_FEATURE','HC_RECORD','EXTERNAL_API') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoWearableReq` enum('NONE','OPTIONAL','REQUIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoExternalService` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoRequiredPermissions` json DEFAULT NULL,
  `manualSignalSource` enum('PHOTO','GROUP_CHECK','SELF_CHECK') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PHOTO',
  `verificationMethod` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`templateId`),
  CONSTRAINT `fkRoutineVerificationTemplate` FOREIGN KEY (`templateId`) REFERENCES `RoutineTemplate` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `SegmentTypeWeight` (
  `segmentType` enum('GLOBAL','COUNTRY','GENDER','AGE_BAND','PLATFORM') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `weight` decimal(6,4) NOT NULL DEFAULT '1.0000',
  `sampleSize` bigint NOT NULL DEFAULT '0',
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`segmentType`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `SystemMetricSnapshot` (
  `id` binary(16) NOT NULL,
  `capturedAt` datetime(6) NOT NULL,
  `cpuUserPct` decimal(5,2) DEFAULT NULL,
  `cpuSystemPct` decimal(5,2) DEFAULT NULL,
  `cpuIoWaitPct` decimal(5,2) DEFAULT NULL,
  `memUsedPct` decimal(5,2) DEFAULT NULL,
  `memUsedBytes` bigint DEFAULT NULL,
  `memTotalBytes` bigint DEFAULT NULL,
  `swapUsedBytes` bigint DEFAULT NULL,
  `swapTotalBytes` bigint DEFAULT NULL,
  `diskUsedPct` decimal(5,2) DEFAULT NULL,
  `diskFreeBytes` bigint DEFAULT NULL,
  `diskTotalBytes` bigint DEFAULT NULL,
  `diskReadsPerSec` decimal(12,2) DEFAULT NULL,
  `diskWritesPerSec` decimal(12,2) DEFAULT NULL,
  `netInBytesPerSec` decimal(16,2) DEFAULT NULL,
  `netOutBytesPerSec` decimal(16,2) DEFAULT NULL,
  `tcpConnEstablished` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ixSystemMetricCapturedAt` (`capturedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `TemplateSegmentScore` (
  `segmentType` enum('GLOBAL','COUNTRY','GENDER','AGE_BAND','PLATFORM') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `segmentValue` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `templateId` bigint unsigned NOT NULL,
  `score` decimal(12,4) NOT NULL DEFAULT '0.0000',
  `selectionCount` int NOT NULL DEFAULT '0',
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`segmentType`,`segmentValue`,`templateId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `TemplateStats` (
  `templateId` bigint NOT NULL,
  `usageCount` bigint NOT NULL DEFAULT '0',
  `completedParticipants` bigint NOT NULL DEFAULT '0',
  `completionRate` decimal(5,4) DEFAULT NULL,
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`templateId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `VerificationDaily` (
  `id` binary(16) NOT NULL,
  `challengeMemberId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `targetDate` date NOT NULL,
  `status` enum('PENDING','SUCCESS','FAILED_PROVISIONAL','FAILED','NOT_TARGET','NOT_REQUIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `method` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `failureReason` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `windowClosesAt` datetime(6) DEFAULT NULL,
  `finalizeAfter` datetime(6) DEFAULT NULL,
  `verifiedAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `verifiedVia` enum('AUTO','MANUAL','MANUAL_FALLBACK','OBJECTION') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `disputeClosesAt` datetime(6) DEFAULT NULL COMMENT 'ì˜ˆë¹„ í´ë°± ì´ì˜ ìœˆë„ìš° ì¢…ë£Œ ì‹œê°. ì´ ì‹œê° ì§€ë‚˜ ì¹¨ë¬µ=ë™ì˜ë¡œ í™•ì •(Â§9.2)',
  `fallbackApprovalStatus` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqVerificationDailyMemberDate` (`challengeMemberId`,`targetDate`),
  KEY `idxVerificationDailyStatusFinalize` (`status`,`finalizeAfter`),
  KEY `idxVerificationDailyFallbackDispute` (`verifiedVia`,`verifiedAt`,`disputeClosesAt`),
  CONSTRAINT `fkVerificationDailyMember` FOREIGN KEY (`challengeMemberId`) REFERENCES `ChallengeMember` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `VerificationMethodResult` (
  `id` binary(16) NOT NULL,
  `verificationDailyId` binary(16) NOT NULL,
  `method` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `polarity` enum('ACHIEVEMENT','CONSTRAINT') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `supported` tinyint(1) NOT NULL DEFAULT '1',
  `status` enum('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `evidence` json DEFAULT NULL,
  `lastEvaluatedAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqMethodResultDailyMethod` (`verificationDailyId`,`method`),
  CONSTRAINT `fkMethodResultDaily` FOREIGN KEY (`verificationDailyId`) REFERENCES `VerificationDaily` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `Watcher` (
  `id` binary(16) NOT NULL,
  `invitationId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `inviterUserId` binary(16) NOT NULL,
  `type` enum('USER','NON_USER') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `channel` enum('IN_APP','SMS') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('INVITED','CONSENTED','ACTIVE','EXPIRED','REVOKED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'INVITED',
  `watcherUserId` binary(16) DEFAULT NULL,
  `contactEnc` varbinary(512) DEFAULT NULL,
  `contactMasked` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `displayName` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `unsubscribeToken` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `consentedAt` datetime(6) DEFAULT NULL,
  `revokedAt` datetime(6) DEFAULT NULL,
  `invitedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqWatcherInvitation` (`invitationId`),
  UNIQUE KEY `uqWatcherUnsubToken` (`unsubscribeToken`),
  KEY `fkWatcherInviter` (`inviterUserId`),
  KEY `ixWatcherChallengeStatus` (`challengeId`,`status`),
  CONSTRAINT `fkWatcherChallenge` FOREIGN KEY (`challengeId`) REFERENCES `Challenge` (`id`),
  CONSTRAINT `fkWatcherInvitation` FOREIGN KEY (`invitationId`) REFERENCES `WatcherInvitation` (`id`),
  CONSTRAINT `fkWatcherInviter` FOREIGN KEY (`inviterUserId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `WatcherBlock` (
  `id` binary(16) NOT NULL,
  `inviterUserId` binary(16) NOT NULL,
  `subjectKey` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `blockedUntil` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixWatcherBlockLookup` (`inviterUserId`,`subjectKey`,`blockedUntil`),
  CONSTRAINT `fkWatcherBlockInviter` FOREIGN KEY (`inviterUserId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `WatcherInvitation` (
  `id` binary(16) NOT NULL,
  `token` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `inviterUserId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `status` enum('INVITED','CONSENTED','EXPIRED','REVOKED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'INVITED',
  `expiresAt` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqWatcherInvitationToken` (`token`),
  KEY `fkWatcherInvitationInviter` (`inviterUserId`),
  KEY `ixWatcherInvitationChallengeStatus` (`challengeId`,`status`),
  CONSTRAINT `fkWatcherInvitationChallenge` FOREIGN KEY (`challengeId`) REFERENCES `Challenge` (`id`),
  CONSTRAINT `fkWatcherInvitationInviter` FOREIGN KEY (`inviterUserId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `WatcherNotification` (
  `id` binary(16) NOT NULL,
  `watcherId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `failedUserId` binary(16) NOT NULL,
  `targetDate` date NOT NULL,
  `channel` enum('IN_APP','SMS') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('PENDING','SENT','SKIPPED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `scheduledAt` datetime(6) NOT NULL,
  `sentAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqWatcherNotificationDay` (`watcherId`,`challengeId`,`targetDate`),
  KEY `fkWatcherNotificationChallenge` (`challengeId`),
  KEY `ixWatcherNotificationDue` (`status`,`scheduledAt`),
  CONSTRAINT `fkWatcherNotificationChallenge` FOREIGN KEY (`challengeId`) REFERENCES `Challenge` (`id`),
  CONSTRAINT `fkWatcherNotificationWatcher` FOREIGN KEY (`watcherId`) REFERENCES `Watcher` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `WatcherOtp` (
  `id` binary(16) NOT NULL,
  `invitationId` binary(16) NOT NULL,
  `phoneEnc` varbinary(512) NOT NULL,
  `phoneHash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `codeHash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `expiresAt` datetime(6) NOT NULL,
  `resendAvailableAt` datetime(6) NOT NULL,
  `consumedAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixWatcherOtpInvitation` (`invitationId`,`createdAt`),
  CONSTRAINT `fkWatcherOtpInvitation` FOREIGN KEY (`invitationId`) REFERENCES `WatcherInvitation` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ========================= 시드(루틴 카탈로그) =========================

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`, `createdAt`, `updatedAt`) VALUES (1,'í—¬ìŠ¤ìž¥ ê°€ì„œ 1ì‹œê°„ ìš´ë™',NULL,'EXERCISE','{\"duration_min\": {\"unit\": \"min\", \"default\": 60}}','ìž¥ì†Œ ì²´ë¥˜ ì‹œê°„ ê°ì§€','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(2,'ì•„ì¹¨ 3km ë‹¬ë¦¬ê¸°',NULL,'EXERCISE','{\"distance_km\": {\"unit\": \"km\", \"default\": 3}, \"duration_min\": {\"unit\": \"min\", \"default\": null}}','ì—°ì† ì¸¡ìœ„ ëˆ„ì ê±°ë¦¬','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(3,'í•˜ë£¨ 8,000ë³´ ê±·ê¸°',NULL,'EXERCISE','{\"steps\": {\"unit\": \"steps\", \"default\": 8000}}','HC ê±¸ìŒìˆ˜ ì§‘ê³„ â€” í° ë³´í–‰ì„¼ì„œ ë¯¸ì‚¬ìš©','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(5,'ì£¼ 3íšŒ ìˆ˜ì˜ìž¥ ê°€ê¸°',NULL,'EXERCISE','{\"times_per_week\": {\"default\": 3}}','ìž¥ì†Œ ë°©ë¬¸ ê°ì§€','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(6,'ìžì „ê±°ë¡œ ì¶œí‡´ê·¼',NULL,'EXERCISE',NULL,'ON_BICYCLE ì „í™˜ ê°ì§€','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(8,'eë¶ ì•± 30ë¶„ ì½ê¸°',NULL,'READING','{\"duration_min\": {\"unit\": \"min\", \"default\": 30}}','ëŒ€ìƒ ì•± ì‚¬ìš©ì‹œê°„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(10,'ì£¼ë§ ë„ì„œê´€ ê°€ê¸°',NULL,'READING',NULL,'ìž¥ì†Œ ë°©ë¬¸ ê°ì§€','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(12,'ì¶œê·¼ê¸¸ ì˜¤ë””ì˜¤ë¶ 20ë¶„',NULL,'READING','{\"duration_min\": {\"unit\": \"min\", \"default\": 20}}','ëŒ€ìƒ ì•± ì‚¬ìš©ì‹œê°„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(13,'í•œ ì¤„ ë…ì„œ ê¸°ë¡ ë‚¨ê¸°ê¸°',NULL,'READING',NULL,'ì¸ì•± ìž‘ì„±ì´ ì¦ê±°','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(15,'ì•„ì¹¨ ëª…ìƒ ì•± 10ë¶„',NULL,'MEDITATION','{\"duration_min\": {\"unit\": \"min\", \"default\": 10}}','ëŒ€ìƒ ì•± ì‚¬ìš©ì‹œê°„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(16,'ìžê¸° ì „ í˜¸í¡ ëª…ìƒ 5ë¶„',NULL,'MEDITATION','{\"duration_min\": {\"unit\": \"min\", \"default\": 5}}','ì•± ì‚¬ìš©ì‹œê°„ + ì‹œê°„ëŒ€','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(17,'ìš”ê°€ì›Â·ëª…ìƒì„¼í„° ê°€ê¸°',NULL,'MEDITATION',NULL,'ìž¥ì†Œ ë°©ë¬¸ ê°ì§€','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(18,'ì›Œì¹˜ ë§ˆìŒì±™ê¹€ ì„¸ì…˜ ê¸°ë¡',NULL,'MEDITATION','{\"duration_min\": {\"unit\": \"min\", \"default\": 10}}','MindfulnessSessionRecord â€” ì›Œì¹˜ í•„ìˆ˜','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(19,'í° ì—†ëŠ” 15ë¶„ (í™”ë©´ OFF)',NULL,'MEDITATION','{\"duration_min\": {\"unit\": \"min\", \"default\": 15}}','í™”ë©´ OFF ìœ ì§€ ê°ì§€','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(20,'ëª…ìƒ ì¼ì§€ ì“°ê¸°',NULL,'MEDITATION',NULL,'ì¸ì•± ìž‘ì„±ì´ ì¦ê±°','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(24,'12ì‹œ ì „ì— ìž ë“¤ê¸°',NULL,'HEALTH','{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"00:00\"}}','ì•¼ê°„ ë§ˆì§€ë§‰ í™”ë©´ OFF ì‹œê°','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(25,'7ì‹œê°„ ì´ìƒ ìˆ˜ë©´',NULL,'HEALTH','{\"sleep_hours\": {\"unit\": \"h\", \"default\": 7}}','Sleep API ìˆ˜ë©´ êµ¬ê°„ (í° ë‹¨ë…)','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(26,'í•˜ë£¨ 10,000ë³´',NULL,'HEALTH','{\"steps\": {\"unit\": \"steps\", \"default\": 10000}}','HC ê±¸ìŒìˆ˜ ì§‘ê³„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(27,'ì£¼ 1íšŒ ì²´ì¤‘ ê¸°ë¡',NULL,'HEALTH','{\"times_per_week\": {\"default\": 1}}','WeightRecord â€” ìŠ¤ë§ˆíŠ¸ì²´ì¤‘ê³„ í•œì • ìžë™','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(28,'ì ì‹¬ í›„ 10ë¶„ ì‚°ì±…',NULL,'HEALTH','{\"duration_min\": {\"unit\": \"min\", \"default\": 10}}','WALKING ì „í™˜ ì„¸ì…˜ (ë³´ì¡°ì‹ í˜¸)','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(29,'ì•„ì¹¨ 7ì‹œ ì „ì— ì¼ì–´ë‚˜ê¸°',NULL,'WAKEUP','{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"07:00\"}}','ë‹¹ì¼ ì²« KEYGUARD_HIDDEN','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(30,'ê¸°ìƒ í›„ 1ì‹œê°„ í° ê¸ˆì§€',NULL,'WAKEUP','{\"duration_min\": {\"unit\": \"min\", \"default\": 60}}','ì‹œê°„ëŒ€ ì•± ì‚¬ìš© 0','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(33,'ì•ŒëžŒ í•œ ë²ˆì— ë„ê³  ë¯¸ì…˜ ìˆ˜í–‰',NULL,'WAKEUP',NULL,'ì¸ì•± ì•ŒëžŒ í•´ì œê°€ ì¦ê±°','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(34,'ì£¼ë§ì—ë„ 8ì‹œ ì „ ê¸°ìƒ',NULL,'WAKEUP','{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"08:00\"}}','ì²« ìž ê¸ˆí•´ì œ ì‹œê° (ìš”ì¼ ì¡°ê±´)','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(36,'9ì‹œ ì „ ì‚¬ë¬´ì‹¤ ë„ì°©',NULL,'WORK','{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"09:00\"}}','ìž¥ì†Œ ë„ì°© ì‹œê°','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(37,'ì˜¤ì „ ë”¥ì›Œí¬ 2ì‹œê°„ (SNS ê¸ˆì§€)',NULL,'WORK','{\"duration_min\": {\"unit\": \"min\", \"default\": 120}}','ì‹œê°„ëŒ€ SNS ì‚¬ìš© ì¸¡ì •','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(38,'ì—…ë¬´ ì‹œìž‘ ì „ íˆ¬ë‘ë¦¬ìŠ¤íŠ¸ ìž‘ì„±',NULL,'WORK',NULL,'ì¸ì•± ìž‘ì„±ì´ ì¦ê±°','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(39,'ì ì‹¬ í›„ ì¹´íŽ˜ì—ì„œ 30ë¶„ ì§‘ì¤‘',NULL,'WORK','{\"duration_min\": {\"unit\": \"min\", \"default\": 30}}','ìž¥ì†Œ ì²´ë¥˜ â€” ë“±ë¡ ìž¥ì†Œ í•œì •','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(40,'í‡´ê·¼ í›„ ì—…ë¬´ ë©”ì‹ ì € ì•ˆ ë³´ê¸°',NULL,'WORK',NULL,'ì‹œê°„ëŒ€ ëŒ€ìƒ ì•± ì‚¬ìš©','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(41,'ê¸ˆìš”ì¼ ì£¼ê°„ íšŒê³  ìž‘ì„±',NULL,'WORK',NULL,'ì¸ì•± ìž‘ì„±ì´ ì¦ê±°','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(43,'ë…ì„œì‹¤ 3ì‹œê°„ ê³µë¶€',NULL,'STUDY','{\"duration_min\": {\"unit\": \"min\", \"default\": 180}}','ìž¥ì†Œ ì²´ë¥˜ ì‹œê°„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(44,'ì¸ê°• 1ì‹œê°„ ë“£ê¸°',NULL,'STUDY','{\"duration_min\": {\"unit\": \"min\", \"default\": 60}}','ëŒ€ìƒ ì•± ì‚¬ìš©ì‹œê°„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(45,'ê³µë¶€ ì‹œê°„ëŒ€ í° ê¸ˆì§€ (19~22ì‹œ)',NULL,'STUDY','{\"end_time\": {\"default\": \"22:00\"}, \"start_time\": {\"default\": \"19:00\"}}','ì‹œê°„ëŒ€ ì•± ì‚¬ìš©','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(46,'ì•”ê¸° ì•± 20ë¶„',NULL,'STUDY','{\"duration_min\": {\"unit\": \"min\", \"default\": 20}}','ëŒ€ìƒ ì•± ì‚¬ìš©ì‹œê°„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(48,'ë„ì„œê´€ 21ì‹œê¹Œì§€ ê³µë¶€',NULL,'STUDY','{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"21:00\"}}','ìž¥ì†Œ ì´íƒˆ ì‹œê°','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(49,'ìŠ¤í„°ë”” ëª¨ìž„ ì°¸ì„',NULL,'STUDY',NULL,'ìž¥ì†Œ ë°©ë¬¸','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(50,'ë“œë¡œìž‰ ì•± 30ë¶„ ê·¸ë¦¬ê¸°',NULL,'HOBBY','{\"duration_min\": {\"unit\": \"min\", \"default\": 30}}','ëŒ€ìƒ ì•± ì‚¬ìš©ì‹œê°„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(52,'í´ë¼ì´ë°ìž¥ ê°€ê¸°',NULL,'HOBBY',NULL,'ìž¥ì†Œ ë°©ë¬¸','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(55,'ê³µë°© ìˆ˜ì—… ì°¸ì„',NULL,'HOBBY',NULL,'ìž¥ì†Œ ë°©ë¬¸','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(62,'ì£¼ 1íšŒ ìž¥ë³´ê¸°',NULL,'COOKING','{\"times_per_week\": {\"default\": 1}}','ë§ˆíŠ¸ ë°©ë¬¸ ê°ì§€','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(64,'ê°€ê³„ë¶€ ì•± 5ë¶„ ìž‘ì„±',NULL,'FINANCE','{\"duration_min\": {\"unit\": \"min\", \"default\": 5}}','ì•± ì‚¬ìš©ì‹œê°„ (ì¼œë†“ê¸° ì¹˜íŒ… ì—¬ì§€)','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(66,'ê²½ì œ ë‰´ìŠ¤ 15ë¶„ ì½ê¸°',NULL,'FINANCE','{\"duration_min\": {\"unit\": \"min\", \"default\": 15}}','ëŒ€ìƒ ì•± ì‚¬ìš©ì‹œê°„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(67,'ì €ë… ì†Œë¹„ íšŒê³  ì“°ê¸°',NULL,'FINANCE',NULL,'ì¸ì•± ìž‘ì„±ì´ ì¦ê±°','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(69,'ì‡¼í•‘ì•± í•˜ë£¨ 30ë¶„ ì´í•˜',NULL,'FINANCE','{\"max_minutes\": {\"unit\": \"min\", \"default\": 30}}','ëŒ€ìƒ ì•± ì‚¬ìš© ìƒí•œ','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(70,'ì£¼ì‹ì•± í•˜ë£¨ 10ë¶„ ì´í•˜',NULL,'FINANCE','{\"max_minutes\": {\"unit\": \"min\", \"default\": 10}}','ëŒ€ìƒ ì•± ì‚¬ìš© ìƒí•œ','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(75,'ë„ë³´ ì¶œê·¼ (ì°¨ ëŒ€ì‹ )',NULL,'ENVIRONMENT',NULL,'WALKING ì „í™˜ + ë„ì°© ê°ì§€','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(80,'ì¹œêµ¬ ì•½ì† ì°¸ì„',NULL,'RELATIONSHIP',NULL,'ìž¥ì†Œ ë°©ë¬¸ â€” ì±Œë¦°ì§€ë³„ ìœ„ì¹˜ í•€ ì „ì œ','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(81,'ì›” 1íšŒ ë³¸ê°€ ë°©ë¬¸',NULL,'RELATIONSHIP','{\"times_per_month\": {\"default\": 1}}','ìž¥ì†Œ ë°©ë¬¸','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(83,'ë™í˜¸íšŒ ì •ê¸°ëª¨ìž„ ì¶œì„',NULL,'RELATIONSHIP',NULL,'ìž¥ì†Œ ë°©ë¬¸','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(86,'ì•…ê¸° í•™ìŠµ ì•± 20ë¶„',NULL,'MUSIC','{\"duration_min\": {\"unit\": \"min\", \"default\": 20}}','ëŒ€ìƒ ì•± ì‚¬ìš©ì‹œê°„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(87,'ì—°ìŠµì‹¤ ê°€ê¸°',NULL,'MUSIC',NULL,'ìž¥ì†Œ ë°©ë¬¸','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(90,'í•©ì£¼Â·ë°´ë“œ ì—°ìŠµ ì°¸ì„',NULL,'MUSIC',NULL,'ìž¥ì†Œ ë°©ë¬¸','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(91,'ìŒì•… ì´ë¡  ì•± 15ë¶„',NULL,'MUSIC','{\"duration_min\": {\"unit\": \"min\", \"default\": 15}}','ëŒ€ìƒ ì•± ì‚¬ìš©ì‹œê°„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(92,'í•˜ë£¨ ì¼ê¸° ì“°ê¸°',NULL,'WRITING',NULL,'ì¸ì•± ìž‘ì„± + ê¸€ìž ìˆ˜ ê²€ì¦','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(93,'ëª¨ë‹ íŽ˜ì´ì§€ (ì•„ì¹¨ ê¸€ì“°ê¸°)',NULL,'WRITING',NULL,'ì¸ì•± ìž‘ì„± + ì‹œê°„ëŒ€ ê²€ì¦','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(94,'ë¸”ë¡œê·¸ ì£¼ 1íšŒ ë°œí–‰',NULL,'WRITING','{\"times_per_week\": {\"default\": 1}}','ë°œí–‰ ê¸€ RSS í™•ì¸','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(95,'ê°ì‚¬ì¼ê¸° 3ì¤„',NULL,'WRITING','{\"lines\": {\"default\": 3}}','ì¸ì•± ìž‘ì„±ì´ ì¦ê±°','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(96,'í•˜ë£¨ 500ìž ì´ìƒ ê¸€ì“°ê¸°',NULL,'WRITING','{\"min_chars\": {\"default\": 500}}','ì¸ì•± ê¸€ìž ìˆ˜ ê²€ì¦','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(99,'1ì¼ 1ì»¤ë°‹',NULL,'CODING','{\"commits_per_day\": {\"default\": 1}}','GitHub ê¸°ì—¬ ë‚´ì—­ ì¡°íšŒ','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(100,'ì½”ë“œí¬ìŠ¤ 1ì¼ 1ë¬¸ì œ','êµ¬ ë°±ì¤€ 1ì¼ 1ì†” ëŒ€ì²´ (BOJ 2026-04 ì¢…ë£ŒÂ·ë¶€í™œ ë¶ˆí™•ì‹¤)','CODING','{\"problems_per_day\": {\"default\": 1}}','Codeforces user.status ê³µê°œ API. í”„ë¡œê·¸ëž˜ë¨¸ìŠ¤ íƒ ì‹œ ìˆ˜ë™(API ì—†ìŒ)','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(101,'ì•Œê³ ë¦¬ì¦˜ ìŠ¤í„°ë”” ì°¸ì„',NULL,'CODING',NULL,'ìž¥ì†Œ ë°©ë¬¸','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(102,'CS ì¸ê°• 30ë¶„',NULL,'CODING','{\"duration_min\": {\"unit\": \"min\", \"default\": 30}}','ëŒ€ìƒ ì•± ì‚¬ìš©ì‹œê°„','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(103,'ì‚¬ì´ë“œ í”„ë¡œì íŠ¸ 1ì‹œê°„',NULL,'CODING','{\"duration_min\": {\"unit\": \"min\", \"default\": 60}}','WakaTime/GitHub ì½”ë”©ì‹œê°„ (PCë¼ í° ì‹ í˜¸ ì—†ìŒ)','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(104,'ê¸°ìˆ  ë¸”ë¡œê·¸ ì£¼ 1íšŒ',NULL,'CODING','{\"times_per_week\": {\"default\": 1}}','ë°œí–‰ ê¸€ RSS í™•ì¸','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673'),(105,'ì½”ë”© ì¤‘ í° ìœ íŠœë¸Œ ê¸ˆì§€',NULL,'CODING',NULL,'ì‹œê°„ëŒ€ ì•± ì‚¬ìš© ì¸¡ì •','2026-07-10 17:30:14.577673','2026-07-10 17:30:14.577673');
INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(2,'PHONE','GPS','OPTIONAL',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_DISTANCE'),(3,'HEALTH_CONNECT','HC_RECORD','OPTIONAL',NULL,'[\"android.permission.health.READ_STEPS\"]','PHOTO','PHOTO'),(5,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(6,'PHONE','ACTIVITY','NONE',NULL,'[\"ACTIVITY_RECOGNITION\"]','PHOTO','GPS_DISTANCE'),(8,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(10,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(12,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(13,'PHONE','APP_FEATURE','NONE',NULL,'[]','GROUP_CHECK','PHOTO'),(15,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(16,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(17,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(18,'HEALTH_CONNECT','HC_RECORD','REQUIRED',NULL,'[\"android.permission.health.READ_MINDFULNESS\"]','PHOTO','PHOTO'),(19,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','GROUP_CHECK','SCREEN_TIME_MAX'),(20,'PHONE','APP_FEATURE','NONE',NULL,'[]','GROUP_CHECK','PHOTO'),(24,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','GROUP_CHECK','SCREEN_TIME_MAX'),(25,'PHONE','SLEEP','OPTIONAL',NULL,'[\"ACTIVITY_RECOGNITION\"]','PHOTO','SLEEP'),(26,'HEALTH_CONNECT','HC_RECORD','OPTIONAL',NULL,'[\"android.permission.health.READ_STEPS\"]','PHOTO','PHOTO'),(27,'HEALTH_CONNECT','HC_RECORD','NONE',NULL,'[\"android.permission.health.READ_WEIGHT\"]','PHOTO','PHOTO'),(28,'PHONE','ACTIVITY','OPTIONAL',NULL,'[\"ACTIVITY_RECOGNITION\"]','PHOTO','GPS_DISTANCE'),(29,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','GROUP_CHECK','WAKE'),(30,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','GROUP_CHECK','SCREEN_TIME_MAX'),(33,'PHONE','APP_FEATURE','NONE',NULL,'[]','GROUP_CHECK','PHOTO'),(34,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','GROUP_CHECK','WAKE'),(36,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','GROUP_CHECK','GPS_PRESENCE'),(37,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','GROUP_CHECK','SCREEN_TIME_MAX'),(38,'PHONE','APP_FEATURE','NONE',NULL,'[]','GROUP_CHECK','PHOTO'),(39,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(40,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','GROUP_CHECK','SCREEN_TIME_MAX'),(41,'PHONE','APP_FEATURE','NONE',NULL,'[]','GROUP_CHECK','PHOTO'),(43,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(44,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(45,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','GROUP_CHECK','SCREEN_TIME_MAX'),(46,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(48,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','GROUP_CHECK','GPS_PRESENCE'),(49,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(50,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(52,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(55,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(62,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(64,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(66,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(67,'PHONE','APP_FEATURE','NONE',NULL,'[]','GROUP_CHECK','PHOTO'),(69,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','GROUP_CHECK','SCREEN_TIME_MAX'),(70,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','GROUP_CHECK','SCREEN_TIME_MAX'),(75,'PHONE','ACTIVITY','OPTIONAL',NULL,'[\"ACTIVITY_RECOGNITION\", \"ACCESS_FINE_LOCATION\"]','PHOTO','GPS_DISTANCE'),(80,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(81,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(83,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(86,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(87,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(90,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(91,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(92,'PHONE','APP_FEATURE','NONE',NULL,'[]','GROUP_CHECK','PHOTO'),(93,'PHONE','APP_FEATURE','NONE',NULL,'[]','GROUP_CHECK','PHOTO'),(94,'EXTERNAL','EXTERNAL_API','NONE','RSS','[]','PHOTO','PHOTO'),(95,'PHONE','APP_FEATURE','NONE',NULL,'[]','GROUP_CHECK','PHOTO'),(96,'PHONE','APP_FEATURE','NONE',NULL,'[]','GROUP_CHECK','PHOTO'),(99,'EXTERNAL','EXTERNAL_API','NONE','GitHub','[]','PHOTO','PHOTO'),(100,'EXTERNAL','EXTERNAL_API','NONE','Codeforces','[]','PHOTO','PHOTO'),(101,'PHONE','GEOFENCE','NONE',NULL,'[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]','PHOTO','GPS_PRESENCE'),(102,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','PHOTO','SCREEN_TIME_MIN'),(103,'EXTERNAL','EXTERNAL_API','NONE','WakaTime','[]','PHOTO','PHOTO'),(104,'EXTERNAL','EXTERNAL_API','NONE','RSS','[]','PHOTO','PHOTO'),(105,'PHONE','USAGE','NONE',NULL,'[\"PACKAGE_USAGE_STATS\"]','GROUP_CHECK','SCREEN_TIME_MAX');

SET FOREIGN_KEY_CHECKS = 1;
