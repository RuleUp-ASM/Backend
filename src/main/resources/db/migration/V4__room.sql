-- ======================================================================
-- room — 방 내부 기능 — 공지·댓글·랭킹·활동 로그
--
-- 이 파일은 한 도메인의 표를 모아 둔다. 파일 안의 순서는 외래키 방향이고,
-- 파일 사이의 순서도 같다 — 앞 파일만 뒤 파일을 가리킨다.
-- ======================================================================

CREATE TABLE `Notice` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `authorId` binary(16) NOT NULL,
  `title` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `pinned` tinyint(1) NOT NULL DEFAULT '0',
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `deletedAt` datetime(6) DEFAULT NULL,
  `activePinnedChallengeId` binary(16) GENERATED ALWAYS AS ((case when ((`pinned` = 1) and (`deletedAt` is null)) then `challengeId` else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqNoticeOneActivePin` (`activePinnedChallengeId`),
  KEY `ixNoticeChallengeDeletedCreated` (`challengeId`,`deletedAt`,`createdAt` DESC),
  KEY `fkNoticeAuthor` (`authorId`),
  CONSTRAINT `fkNoticeAuthor` FOREIGN KEY (`authorId`) REFERENCES `users` (`id`),
  CONSTRAINT `fkNoticeChallenge` FOREIGN KEY (`challengeId`) REFERENCES `challenges` (`id`)
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

CREATE TABLE `room_comments` (
  `id` binary(16) NOT NULL,
  `challenge_id` binary(16) NOT NULL,
  `target_type` enum('NOTICE','VERIFY_EVENT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_id` binary(16) NOT NULL,
  `author_id` binary(16) NOT NULL,
  `parent_comment_id` binary(16) DEFAULT NULL,
  `body` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_room_comments_target` (`target_type`,`target_id`,`created_at`,`id`),
  KEY `ix_room_comments_parent` (`parent_comment_id`,`created_at`,`id`),
  KEY `fk_room_comments_challenge` (`challenge_id`),
  KEY `fk_room_comments_author` (`author_id`),
  CONSTRAINT `fk_room_comments_author` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_room_comments_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`),
  CONSTRAINT `fk_room_comments_parent` FOREIGN KEY (`parent_comment_id`) REFERENCES `room_comments` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `RoomActivityLog` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `actorId` binary(16) DEFAULT NULL,
  `entityType` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `entityId` binary(16) DEFAULT NULL,
  `action` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload` text COLLATE utf8mb4_unicode_ci,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixRoomLogChallengeCreated` (`challengeId`,`createdAt`),
  KEY `ixRoomLogEntity` (`entityType`,`entityId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_cross_ranking_members` (
  `challenge_id` binary(16) NOT NULL,
  `user_id` binary(16) NOT NULL,
  `success_count` int NOT NULL,
  `total_count` int NOT NULL,
  PRIMARY KEY (`challenge_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `challenge_cross_ranking_snapshot` (
  `mode` enum('SOLO','GROUP') COLLATE utf8mb4_unicode_ci NOT NULL,
  `challenge_id` binary(16) NOT NULL,
  `rank_no` int DEFAULT NULL,
  `title` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `member_count` int NOT NULL,
  `success_count` int NOT NULL,
  `total_count` int NOT NULL,
  `success_rate` decimal(8,4) NOT NULL,
  `snapshot_at` datetime(6) NOT NULL,
  PRIMARY KEY (`mode`,`challenge_id`),
  KEY `ix_cross_ranking_page` (`mode`,`rank_no`,`challenge_id`),
  KEY `fk_cross_ranking_challenge` (`challenge_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_final_ranking` (
  `challenge_id` binary(16) NOT NULL,
  `user_id` binary(16) NOT NULL,
  `rank_no` int DEFAULT NULL,
  `score_snapshot` decimal(6,4) DEFAULT NULL,
  `success_count` int DEFAULT NULL,
  `participations` int DEFAULT NULL,
  `archived_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`challenge_id`,`user_id`),
  KEY `idx_final_ranking_rank` (`challenge_id`,`rank_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `room_job_locks` (
  `job_name` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_run_on` date DEFAULT NULL,
  PRIMARY KEY (`job_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 기준 데이터 — 코드가 이 행들의 존재를 전제한다.

INSERT INTO `room_job_locks` (`job_name`, `last_run_on`) VALUES ('CROSS_RANKING', NULL);
