-- 방 내부·운영 기능 스펙/백엔드 테크스펙/API 계약 정합.
-- 기존 테이블은 호환성을 유지한 채 expand하고, 신규 기능은 독립 테이블로 추가한다.

ALTER TABLE `Notice`
    ADD COLUMN `deletedAt` datetime(6) NULL AFTER `updatedAt`,
    ADD COLUMN `activePinnedChallengeId` binary(16)
      GENERATED ALWAYS AS (CASE WHEN `pinned` = 1 AND `deletedAt` IS NULL THEN `challengeId` ELSE NULL END) STORED;
CREATE UNIQUE INDEX `uqNoticeOneActivePin` ON `Notice` (`activePinnedChallengeId`);
CREATE INDEX `ixNoticeChallengeDeletedCreated`
    ON `Notice` (`challengeId`, `deletedAt`, `createdAt` DESC);

ALTER TABLE `VerificationDaily`
    ADD COLUMN `shareableAt` datetime(6) NULL AFTER `disputeClosesAt`;
CREATE INDEX `ixVerificationDailyThread`
    ON `VerificationDaily` (`challengeId`, `status`, `shareableAt`, `verifiedAt`);

ALTER TABLE `challenges`
    MODIFY COLUMN `owner_id` binary(16) NULL,
    ADD COLUMN `owner_type` enum('USER','BOT') NOT NULL DEFAULT 'USER' AFTER `owner_id`,
    ADD COLUMN `owner_granted_at` datetime(6) NULL AFTER `owner_type`;
UPDATE `challenges` SET `owner_granted_at` = `created_at` WHERE `owner_granted_at` IS NULL;

ALTER TABLE `challenge_members`
    ADD COLUMN `left_type` enum('LEAVE','KICK') NULL,
    ADD COLUMN `left_at` datetime(6) NULL,
    ADD COLUMN `kick_reason` varchar(500) NULL,
    ADD COLUMN `kick_count` int NOT NULL DEFAULT 0,
    ADD COLUMN `rejoin_available_at` datetime(6) NULL;

CREATE TABLE `room_comments` (
  `id`                binary(16) NOT NULL,
  `challenge_id`      binary(16) NOT NULL,
  `target_type`       enum('NOTICE','VERIFY_EVENT') NOT NULL,
  `target_id`         binary(16) NOT NULL,
  `author_id`         binary(16) NOT NULL,
  `parent_comment_id` binary(16) NULL,
  `body`              varchar(500) NOT NULL,
  `deleted_at`        datetime(6) NULL,
  `created_at`        datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`        datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_room_comments_target` (`target_type`, `target_id`, `created_at`, `id`),
  KEY `ix_room_comments_parent` (`parent_comment_id`, `created_at`, `id`),
  CONSTRAINT `fk_room_comments_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`),
  CONSTRAINT `fk_room_comments_author` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_room_comments_parent` FOREIGN KEY (`parent_comment_id`) REFERENCES `room_comments` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_invitations` (
  `id`          binary(16) NOT NULL,
  `challenge_id` binary(16) NOT NULL,
  `inviter_id`  binary(16) NOT NULL,
  `token_hash`  binary(32) NOT NULL,
  `expires_at`  datetime(6) NOT NULL,
  `used_at`     datetime(6) NULL,
  `created_at`  datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_challenge_invitations_token` (`token_hash`),
  KEY `ix_challenge_invitations_challenge` (`challenge_id`, `expires_at`),
  CONSTRAINT `fk_challenge_invitations_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`),
  CONSTRAINT `fk_challenge_invitations_inviter` FOREIGN KEY (`inviter_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `reports` (
  `id`                  binary(16) NOT NULL,
  `reporter_id`         binary(16) NOT NULL,
  `target_type`         enum('USER','CHALLENGE') NOT NULL,
  `target_user_id`      binary(16) NULL,
  `target_challenge_id` binary(16) NULL,
  `context_type`        varchar(30) NOT NULL,
  `reason`              varchar(30) NOT NULL,
  `detail`              varchar(1000) NULL,
  `duplicate_report`    tinyint(1) NOT NULL DEFAULT 0,
  `review_status`       enum('PENDING','VALID','INVALID') NOT NULL DEFAULT 'PENDING',
  `created_at`          datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_reports_reporter_target` (`reporter_id`, `target_type`, `target_user_id`, `target_challenge_id`, `created_at`),
  KEY `ix_reports_review` (`review_status`, `created_at`),
  CONSTRAINT `fk_reports_reporter` FOREIGN KEY (`reporter_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `blacklist_users` (
  `owner_id`          binary(16) NOT NULL,
  `blocked_user_id`   binary(16) NOT NULL,
  `source_report_id`  binary(16) NULL,
  `created_at`        datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`owner_id`, `blocked_user_id`),
  CONSTRAINT `fk_blacklist_users_owner` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_blacklist_users_blocked` FOREIGN KEY (`blocked_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `blacklist_challenges` (
  `owner_id`             binary(16) NOT NULL,
  `blocked_challenge_id` binary(16) NOT NULL,
  `source_report_id`     binary(16) NULL,
  `created_at`           datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`owner_id`, `blocked_challenge_id`),
  CONSTRAINT `fk_blacklist_challenges_owner` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_blacklist_challenges_challenge` FOREIGN KEY (`blocked_challenge_id`) REFERENCES `challenges` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `report_admin_review_queue` (
  `id`                  binary(16) NOT NULL,
  `target_type`         enum('USER','CHALLENGE') NOT NULL,
  `target_user_id`      binary(16) NULL,
  `target_challenge_id` binary(16) NULL,
  `status`              enum('PENDING','RESOLVED') NOT NULL DEFAULT 'PENDING',
  `created_at`          datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_report_admin_review_target` (`target_type`, `target_user_id`, `target_challenge_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `report_suspensions` (
  `user_id`         binary(16) NOT NULL,
  `suspended_until` datetime(6) NOT NULL,
  `reason`          varchar(100) NOT NULL,
  `updated_at`      datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_report_suspensions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `Notification`
    MODIFY COLUMN `type` varchar(40) NOT NULL,
    ADD COLUMN `class` enum('CHALLENGE','ROOM','TIER','SYSTEM') NOT NULL DEFAULT 'SYSTEM' AFTER `type`,
    ADD COLUMN `deeplink` varchar(500) NULL AFTER `message`,
    ADD COLUMN `deletedAt` datetime(6) NULL AFTER `readAt`;
CREATE INDEX `ixNotificationList`
    ON `Notification` (`userId`, `deletedAt`, `createdAt` DESC, `id`);

CREATE TABLE `notification_settings` (
  `user_id`              binary(16) NOT NULL,
  `challenge_activity`   tinyint(1) NOT NULL DEFAULT 1,
  `room_activity`        tinyint(1) NOT NULL DEFAULT 1,
  `tier_activity`        tinyint(1) NOT NULL DEFAULT 1,
  `marketing`            tinyint(1) NOT NULL DEFAULT 0,
  `night_push`           tinyint(1) NOT NULL DEFAULT 0,
  `muted_challenge_ids`  json NOT NULL,
  `updated_at`           datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_notification_settings_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
