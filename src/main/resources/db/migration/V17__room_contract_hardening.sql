-- 방 내부 기능 계약 보강: 신고 판정 근거, 안정적인 외부 랭킹 스냅샷,
-- Phase 1 응답 호환을 위해 보존해야 하는 Phase 2 저장소를 복구한다.

ALTER TABLE `reports`
    ADD COLUMN `context_id` binary(16) NULL AFTER `context_type`,
    ADD COLUMN `behavior_violation` tinyint(1) NULL AFTER `review_status`;
CREATE INDEX `ix_reports_behavior_threshold`
    ON `reports` (`target_user_id`, `target_challenge_id`, `review_status`, `behavior_violation`, `duplicate_report`);

ALTER TABLE `report_suspensions`
    ADD COLUMN `suspension_count` int NOT NULL DEFAULT 0 AFTER `reason`;

-- 공동 관리자는 폐기됐다. 과거 데이터는 MEMBER로 정규화하고 DB가 다시 MANAGER를 받지 않게 한다.
UPDATE `challenge_members` SET `role`='MEMBER' WHERE `role`='MANAGER';
ALTER TABLE `challenge_members` MODIFY COLUMN `role` enum('OWNER','MEMBER') NOT NULL DEFAULT 'MEMBER';

CREATE TABLE `challenge_cross_ranking_snapshot` (
  `mode`              enum('SOLO','GROUP') NOT NULL,
  `challenge_id`      binary(16) NOT NULL,
  `rank_no`           int NULL,
  `title`             varchar(30) NOT NULL,
  `member_count`      int NOT NULL,
  `success_count`     int NOT NULL,
  `total_count`       int NOT NULL,
  `success_rate`      decimal(8,4) NOT NULL,
  `snapshot_at`       datetime(6) NOT NULL,
  PRIMARY KEY (`mode`, `challenge_id`),
  KEY `ix_cross_ranking_page` (`mode`, `rank_no`, `challenge_id`),
  CONSTRAINT `fk_cross_ranking_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `room_job_locks` (
  `job_name` varchar(40) NOT NULL,
  PRIMARY KEY (`job_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO `room_job_locks` (`job_name`) VALUES ('CROSS_RANKING');

CREATE TABLE `Notice` (
  `id`                      binary(16) NOT NULL,
  `challengeId`             binary(16) NOT NULL,
  `authorId`                binary(16) NOT NULL,
  `title`                   varchar(100) NOT NULL,
  `content`                 varchar(2000) NOT NULL,
  `pinned`                  tinyint(1) NOT NULL DEFAULT 0,
  `createdAt`               datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt`               datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `deletedAt`               datetime(6) NULL,
  `activePinnedChallengeId` binary(16)
      GENERATED ALWAYS AS (CASE WHEN `pinned` = 1 AND `deletedAt` IS NULL THEN `challengeId` ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqNoticeOneActivePin` (`activePinnedChallengeId`),
  KEY `ixNoticeChallengeDeletedCreated` (`challengeId`, `deletedAt`, `createdAt` DESC),
  KEY `fkNoticeAuthor` (`authorId`),
  CONSTRAINT `fkNoticeAuthor` FOREIGN KEY (`authorId`) REFERENCES `users` (`id`),
  CONSTRAINT `fkNoticeChallenge` FOREIGN KEY (`challengeId`) REFERENCES `challenges` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `NoticeRead` (
  `id`          binary(16) NOT NULL,
  `noticeId`    binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `userId`      binary(16) NOT NULL,
  `readAt`      datetime(6) NOT NULL,
  `createdAt`   datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqNoticeReadNoticeUser` (`noticeId`, `userId`),
  KEY `ixNoticeReadChallengeUser` (`challengeId`, `userId`),
  KEY `fkNoticeReadUser` (`userId`),
  CONSTRAINT `fkNoticeReadNotice` FOREIGN KEY (`noticeId`) REFERENCES `Notice` (`id`),
  CONSTRAINT `fkNoticeReadUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

CREATE TABLE `RoomActivityLog` (
  `id`           binary(16) NOT NULL,
  `challengeId`  binary(16) NOT NULL,
  `actorId`      binary(16) NULL,
  `entityType`   varchar(40) NOT NULL,
  `entityId`     binary(16) NULL,
  `action`       varchar(20) NOT NULL,
  `payload`      text NULL,
  `createdAt`    datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixRoomLogChallengeCreated` (`challengeId`, `createdAt`),
  KEY `ixRoomLogEntity` (`entityType`, `entityId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
