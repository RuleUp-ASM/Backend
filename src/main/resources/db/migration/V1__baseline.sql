-- 경로: src/main/resources/db/migration/V1__baseline.sql
-- RuleUp 전체 스키마 baseline (단일 파일). 기존 V1~V21 의 CREATE/ALTER/INDEX 를 최종 형태로 통합했다.
--   · 처음 띄우는(fresh) DB 용. 증분 ALTER 없이 각 테이블을 최종 컬럼·인덱스로 한 번에 생성한다.
--   · 인덱스는 실제 소비 쿼리가 있는 것만 유지(미사용/중복 제거).
--   · RoutineTemplate/RoutineVerification 카탈로그 시드 포함(운영이 관리하는 읽기 전용 지식베이스).
--   · 테이블은 알파벳순(mysqldump)이라 FK 순서 무관하도록 FOREIGN_KEY_CHECKS 를 잠깐 끈다.

SET FOREIGN_KEY_CHECKS = 0;

-- ========================= 스키마 =========================

CREATE TABLE `Challenge` (
  `id` binary(16) NOT NULL,
  `creatorId` binary(16) NOT NULL,
  `title` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `imageUrl` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `participationType` enum('SOLO','GROUP') COLLATE utf8mb4_unicode_ci NOT NULL,
  `minMannerTemperature` decimal(4,1) DEFAULT NULL,
  `repeatDays` json NOT NULL,
  `durationDays` int NOT NULL,
  `startDate` date NOT NULL,
  `endDate` date NOT NULL,
  `templateId` bigint unsigned DEFAULT NULL,
  `verificationConfig` json NOT NULL,
  `params` json NOT NULL,
  `penaltyConfig` json NOT NULL,
  `rewardConfig` json NOT NULL,
  `anonymity` enum('REAL','ANONYMOUS') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'REAL',
  `status` enum('RECRUITING','ACTIVE','COMPLETED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'RECRUITING',
  `moderationStatus` enum('PENDING_REVIEW','APPROVED','REJECTED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING_REVIEW',
  `moderationDecidedAt` datetime(6) DEFAULT NULL,
  `fixDeadline` datetime(6) DEFAULT NULL,
  `aiAssisted` tinyint(1) NOT NULL DEFAULT '0',
  `participantCount` int NOT NULL DEFAULT '0',
  `trendingScore` double NOT NULL DEFAULT '0',
  `failCount` int NOT NULL DEFAULT '0',
  `verificationType` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `deletedAt` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fkChallengeCreator` (`creatorId`),
  KEY `idx_challenge_explore` (`deletedAt`,`moderationStatus`,`status`,`endDate`),
  KEY `idx_challenge_template` (`templateId`),
  CONSTRAINT `fkChallengeCreator` FOREIGN KEY (`creatorId`) REFERENCES `User` (`id`),
  CONSTRAINT `ckChallengeDuration` CHECK ((`durationDays` >= 1)),
  CONSTRAINT `ckChallengeMinManner` CHECK (((`minMannerTemperature` is null) or (`minMannerTemperature` >= 0.0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `ChallengeMember` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `role` enum('OWNER','MEMBER') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MEMBER',
  `status` enum('PENDING','ACTIVE','LEFT','REMOVED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `joinedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `scheduleType` enum('FIXED_DAYS','FREQUENCY') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'FIXED_DAYS',
  `targetDays` int NOT NULL DEFAULT '0',
  `successDays` int NOT NULL DEFAULT '0',
  `failDays` int NOT NULL DEFAULT '0',
  `progressRate` decimal(5,2) NOT NULL DEFAULT '0.00',
  `todayStatus` enum('SUCCESS','PENDING','FAILED','NOT_TARGET','NOT_REQUIRED') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lastSyncedAt` datetime(6) DEFAULT NULL,
  `periodUnit` enum('WEEK','MONTH') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `periodTarget` int DEFAULT NULL,
  `curPeriodStart` date DEFAULT NULL,
  `curPeriodEnd` date DEFAULT NULL,
  `curPeriodCompleted` int DEFAULT NULL,
  `periodsTotal` int DEFAULT NULL,
  `periodsMet` int DEFAULT NULL,
  `setupStatus` enum('PENDING_SETUP','READY') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING_SETUP' COMMENT 'ìµœì´ˆ ì§„ìž… ì…‹ì—… ìƒíƒœ. READY ì „ê¹Œì§€ í‰ê°€ ìŠ¤í‚µ(Â§4)',
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
  CONSTRAINT `fkMemberUser` FOREIGN KEY (`userId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `Notification` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `type` enum('NICKNAME_REJECTED','PROFILE_IMAGE_REJECTED','CHALLENGE_NAME_REJECTED','CHALLENGE_CLOSED','CHALLENGE_APPROVED','CHALLENGE_JOIN_REQUESTED','CHALLENGE_MEMBER_APPROVED','CHALLENGE_MEMBER_REJECTED','FALLBACK_APPROVED','FALLBACK_REJECTED','WATCHER_ROUTINE_FAILED','SYSTEM') COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `message` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `readAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idxNotificationUserCreated` (`userId`,`createdAt`),
  CONSTRAINT `fkNotificationUser` FOREIGN KEY (`userId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `RefreshToken` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `tokenHash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expiresAt` datetime(6) NOT NULL,
  `revokedAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqRefreshTokenHash` (`tokenHash`),
  KEY `fkRefreshTokenUser` (`userId`),
  CONSTRAINT `fkRefreshTokenUser` FOREIGN KEY (`userId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `ReputationScore` (
  `userId` binary(16) NOT NULL,
  `mannerTemperature` decimal(4,1) NOT NULL DEFAULT '36.5',
  PRIMARY KEY (`userId`),
  CONSTRAINT `fkReputationUser` FOREIGN KEY (`userId`) REFERENCES `User` (`id`),
  CONSTRAINT `ckReputationMannerTemp` CHECK ((`mannerTemperature` >= 0.0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `RoutineOutcome` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `challengeMemberId` binary(16) NOT NULL,
  `templateId` bigint unsigned DEFAULT NULL,
  `category` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `targetDate` date NOT NULL,
  `status` enum('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `verifiedVia` enum('AUTO','MANUAL','MANUAL_FALLBACK') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `failureReason` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `confirmedAt` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqRoutineOutcomeMemberDate` (`challengeId`,`userId`,`targetDate`),
  KEY `ixRoutineOutcomeConfirmedAt` (`confirmedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `RoutineTemplate` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category` enum('EXERCISE','READING','MEDITATION','HEALTH','WAKEUP','WORK','STUDY','HOBBY','COOKING','FINANCE','ENVIRONMENT','RELATIONSHIP','MUSIC','WRITING','CODING') COLLATE utf8mb4_unicode_ci NOT NULL,
  `paramSchema` json DEFAULT NULL,
  `rationale` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  FULLTEXT KEY `ftxNameDesc` (`name`,`description`) /*!50100 WITH PARSER `ngram` */ 
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `RoutineVerification` (
  `templateId` bigint unsigned NOT NULL,
  `autoVerificationType` enum('PHONE','HEALTH_CONNECT','EXTERNAL') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoSignalSource` enum('GEOFENCE','GPS','ACTIVITY','SLEEP','USAGE','APP_FEATURE','HC_RECORD','EXTERNAL_API') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoWearableReq` enum('NONE','OPTIONAL','REQUIRED') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoExternalService` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoRequiredPermissions` json DEFAULT NULL,
  `manualSignalSource` enum('PHOTO','GROUP_CHECK','SELF_CHECK') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PHOTO',
  `verificationMethod` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`templateId`),
  CONSTRAINT `fkRoutineVerificationTemplate` FOREIGN KEY (`templateId`) REFERENCES `RoutineTemplate` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `SegmentTypeWeight` (
  `segmentType` enum('GLOBAL','COUNTRY','GENDER','AGE_BAND','PLATFORM') COLLATE utf8mb4_unicode_ci NOT NULL,
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
  `segmentType` enum('GLOBAL','COUNTRY','GENDER','AGE_BAND','PLATFORM') COLLATE utf8mb4_unicode_ci NOT NULL,
  `segmentValue` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
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
CREATE TABLE `User` (
  `id` binary(16) NOT NULL,
  `oauthProvider` enum('KAKAO','NAVER','GOOGLE','APPLE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `oauthSubject` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `email` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `nickname` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `nicknameStatus` enum('PENDING','APPROVED','REJECTED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `tempNickname` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `profileImageUrl` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `profileImageStatus` enum('PENDING','APPROVED','REJECTED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `moderationCheckedAt` datetime(6) DEFAULT NULL,
  `interestCategories` json NOT NULL,
  `countryCode` char(2) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `birthDate` date DEFAULT NULL,
  `gender` enum('MALE','FEMALE') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `platform` enum('ANDROID','IOS') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `appVersionCode` int DEFAULT NULL,
  `appVersionName` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `osVersion` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sdkInt` int DEFAULT NULL,
  `deviceModel` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `manufacturer` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lowRam` tinyint(1) DEFAULT NULL,
  `deviceInfoUpdatedAt` datetime(6) DEFAULT NULL,
  `nicknameChangedAt` datetime(6) DEFAULT NULL,
  `deletedAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqUserNickname` (`nickname`),
  UNIQUE KEY `uqUserOauth` (`oauthProvider`,`oauthSubject`),
  CONSTRAINT `ckUserProfileImageUrl` CHECK (((`profileImageUrl` is null) or regexp_like(`profileImageUrl`,_utf8mb4'^https?://')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `UserAgreement` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `agreementType` enum('TERMS','PRIVACY','MARKETING') COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `agreedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `revokedAt` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fkUserAgreementUser` (`userId`),
  CONSTRAINT `fkUserAgreementUser` FOREIGN KEY (`userId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `VerificationDaily` (
  `id` binary(16) NOT NULL,
  `challengeMemberId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `targetDate` date NOT NULL,
  `status` enum('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `method` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `failureReason` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `windowClosesAt` datetime(6) DEFAULT NULL,
  `finalizeAfter` datetime(6) DEFAULT NULL,
  `verifiedAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `verifiedVia` enum('AUTO','MANUAL','MANUAL_FALLBACK') COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'í™•ì • ê²½ë¡œ. AUTO=ì‹ í˜¸ìžë™ / MANUAL=ì •ê·œìˆ˜ë™ / MANUAL_FALLBACK=ì˜ˆë¹„í´ë°±(Â§11.6)',
  `disputeClosesAt` datetime(6) DEFAULT NULL COMMENT 'ì˜ˆë¹„ í´ë°± ì´ì˜ ìœˆë„ìš° ì¢…ë£Œ ì‹œê°. ì´ ì‹œê° ì§€ë‚˜ ì¹¨ë¬µ=ë™ì˜ë¡œ í™•ì •(Â§9.2)',
  `fallbackApprovalStatus` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqVerificationDailyMemberDate` (`challengeMemberId`,`targetDate`),
  KEY `idxVerificationDailyStatusFinalize` (`status`,`finalizeAfter`),
  KEY `idxVerificationDailyFallbackDispute` (`verifiedVia`,`verifiedAt`,`disputeClosesAt`),
  CONSTRAINT `fkVerificationDailyMember` FOREIGN KEY (`challengeMemberId`) REFERENCES `ChallengeMember` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `VerificationMethodResult` (
  `id` binary(16) NOT NULL,
  `verificationDailyId` binary(16) NOT NULL,
  `method` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `polarity` enum('ACHIEVEMENT','CONSTRAINT') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `supported` tinyint(1) NOT NULL DEFAULT '1',
  `status` enum('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
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
  `type` enum('USER','NON_USER') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `channel` enum('IN_APP','SMS') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('INVITED','CONSENTED','ACTIVE','EXPIRED','REVOKED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'INVITED',
  `watcherUserId` binary(16) DEFAULT NULL,
  `contactEnc` varbinary(512) DEFAULT NULL,
  `contactMasked` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `displayName` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `unsubscribeToken` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
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
  CONSTRAINT `fkWatcherInviter` FOREIGN KEY (`inviterUserId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `WatcherBlock` (
  `id` binary(16) NOT NULL,
  `inviterUserId` binary(16) NOT NULL,
  `subjectKey` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `blockedUntil` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixWatcherBlockLookup` (`inviterUserId`,`subjectKey`,`blockedUntil`),
  CONSTRAINT `fkWatcherBlockInviter` FOREIGN KEY (`inviterUserId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `WatcherInvitation` (
  `id` binary(16) NOT NULL,
  `token` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `inviterUserId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `status` enum('INVITED','CONSENTED','EXPIRED','REVOKED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'INVITED',
  `expiresAt` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqWatcherInvitationToken` (`token`),
  KEY `fkWatcherInvitationInviter` (`inviterUserId`),
  KEY `ixWatcherInvitationChallengeStatus` (`challengeId`,`status`),
  CONSTRAINT `fkWatcherInvitationChallenge` FOREIGN KEY (`challengeId`) REFERENCES `Challenge` (`id`),
  CONSTRAINT `fkWatcherInvitationInviter` FOREIGN KEY (`inviterUserId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `WatcherNotification` (
  `id` binary(16) NOT NULL,
  `watcherId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `failedUserId` binary(16) NOT NULL,
  `targetDate` date NOT NULL,
  `channel` enum('IN_APP','SMS') COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('PENDING','SENT','SKIPPED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
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
  `phoneHash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `codeHash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
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
