-- ======================================================================
-- room — 방 내부 기능 — 랭킹·활동 로그
--
-- 이 파일은 한 도메인의 표를 모아 둔다. 파일 안의 순서는 외래키 방향이고,
-- 파일 사이의 순서도 같다 — 앞 파일만 뒤 파일을 가리킨다.
-- ======================================================================

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
