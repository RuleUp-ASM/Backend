-- 마이프로필: 평판 이력 인프라.
--  1) ReputationScore 에 역대 최고온도(peak) 컬럼 추가
--  2) ReputationSnapshot(일별 온도 스냅샷) — 온도 상세 recentChanges + 통계 mannerDelta 원천
--  3) Milestone(append-only 마일스톤) — 평판 히스토리 피드

-- 1) 역대 최고온도
ALTER TABLE `ReputationScore`
    ADD COLUMN `peakTemperature` decimal(5,2) DEFAULT NULL,
    ADD COLUMN `peakAchievedAt` date DEFAULT NULL;

-- 2) 일별 온도 스냅샷(배치가 하루 1행 멱등 적재)
CREATE TABLE `ReputationSnapshot` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `snapshotDate` date NOT NULL,
  `temperature` decimal(5,2) NOT NULL,
  `delta` decimal(6,2) NOT NULL DEFAULT '0.00',
  `label` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqReputationSnapshotUserDate` (`userId`,`snapshotDate`),
  KEY `ixReputationSnapshotUserDate` (`userId`,`snapshotDate`),
  CONSTRAINT `fkReputationSnapshotUser` FOREIGN KEY (`userId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3) 마일스톤(append-only, 유저×타입×키 멱등)
CREATE TABLE `Milestone` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `type` enum('TIER_REACHED','STREAK','FIRST_COMPLETION','SIGNUP') COLLATE utf8mb4_unicode_ci NOT NULL,
  `dedupKey` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL,
  `label` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `achievedAt` date NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqMilestoneUserTypeKey` (`userId`,`type`,`dedupKey`),
  KEY `ixMilestoneUserAchieved` (`userId`,`achievedAt`),
  CONSTRAINT `fkMilestoneUser` FOREIGN KEY (`userId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
