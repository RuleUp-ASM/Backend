-- 방 내부기능: 챌린지 공지 + 읽음.
CREATE TABLE `Notice` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `authorId` binary(16) NOT NULL,
  `title` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `pinned` tinyint(1) NOT NULL DEFAULT '0',
  `deletedAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixNoticeChallengePinned` (`challengeId`,`deletedAt`,`pinned`,`createdAt`),
  CONSTRAINT `fkNoticeChallenge` FOREIGN KEY (`challengeId`) REFERENCES `Challenge` (`id`),
  CONSTRAINT `fkNoticeAuthor` FOREIGN KEY (`authorId`) REFERENCES `User` (`id`)
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
  CONSTRAINT `fkNoticeReadNotice` FOREIGN KEY (`noticeId`) REFERENCES `Notice` (`id`),
  CONSTRAINT `fkNoticeReadUser` FOREIGN KEY (`userId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
