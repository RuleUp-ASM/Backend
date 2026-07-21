-- 방 내부기능: 챌린지 공지 + 읽음 + 방 내부 기록 활동 로그.
-- 공지는 소프트삭제 없이 물리 삭제(방 관리 부담 완화). 삭제/수정 이력은 RoomActivityLog로 보존.
-- 알림 타입에 NOTICE_CREATED 추가(공지 fan-out).
ALTER TABLE `Notification`
    MODIFY `type` enum('NICKNAME_REJECTED','PROFILE_IMAGE_REJECTED','CHALLENGE_NAME_REJECTED',
        'CHALLENGE_CLOSED','CHALLENGE_APPROVED','CHALLENGE_JOIN_REQUESTED','CHALLENGE_MEMBER_APPROVED',
        'CHALLENGE_MEMBER_REJECTED','FALLBACK_APPROVED','FALLBACK_REJECTED','WATCHER_ROUTINE_FAILED',
        'NOTICE_CREATED','SYSTEM') COLLATE utf8mb4_unicode_ci NOT NULL;

CREATE TABLE `Notice` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `authorId` binary(16) NOT NULL,
  `title` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `pinned` tinyint(1) NOT NULL DEFAULT '0',
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixNoticeChallengePinned` (`challengeId`,`pinned`,`createdAt`),
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

-- 방 내부 기록 활동 로그(append-only). 챌린지/유저 FK 없음 → 방(챌린지) 하드삭제 후에도 감사 로그로 생존.
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
