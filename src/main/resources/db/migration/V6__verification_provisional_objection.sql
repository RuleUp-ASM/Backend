-- 인증 v3: 실패 2단계(잠정 실패) + 이의 제기.
--  1) VerificationDaily.status ENUM 에 FAILED_PROVISIONAL 추가
--  2) VerificationDaily.verifiedVia ENUM 에 OBJECTION 추가
--  3) ChallengeMember.todayStatus(비정규화 캐시) ENUM 에 FAILED_PROVISIONAL 추가
--  4) Objection(이의 제기) 테이블 신설

-- 1) 하루 판정 상태: 잠정 실패 추가(FAILED 앞에 배치)
ALTER TABLE `VerificationDaily`
    MODIFY `status` enum('PENDING','SUCCESS','FAILED_PROVISIONAL','FAILED','NOT_TARGET','NOT_REQUIRED')
        COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING';

-- 2) 확정 경로: 이의 제기 승인(OBJECTION) 추가
ALTER TABLE `VerificationDaily`
    MODIFY `verifiedVia` enum('AUTO','MANUAL','MANUAL_FALLBACK','OBJECTION')
        COLLATE utf8mb4_unicode_ci DEFAULT NULL;

-- 3) 비정규화 오늘 상태 캐시(진행률 뱃지)
ALTER TABLE `ChallengeMember`
    MODIFY `todayStatus` enum('SUCCESS','PENDING','FAILED_PROVISIONAL','FAILED','NOT_TARGET','NOT_REQUIRED')
        COLLATE utf8mb4_unicode_ci DEFAULT NULL;

-- 4) 이의 제기
CREATE TABLE `Objection` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `challengeMemberId` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `targetDate` date NOT NULL,
  `type` enum('FAILURE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `imageUrl` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('PENDING','APPROVED','REJECTED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `deadline` datetime(6) NOT NULL,
  `decidedBy` binary(16) DEFAULT NULL,
  `decidedAt` datetime(6) DEFAULT NULL,
  `decisionReason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqObjectionMemberDate` (`challengeMemberId`,`targetDate`),
  KEY `ixObjectionChallengeStatus` (`challengeId`,`status`),
  CONSTRAINT `fkObjectionChallenge` FOREIGN KEY (`challengeId`) REFERENCES `Challenge` (`id`),
  CONSTRAINT `fkObjectionMember` FOREIGN KEY (`challengeMemberId`) REFERENCES `ChallengeMember` (`id`),
  CONSTRAINT `fkObjectionUser` FOREIGN KEY (`userId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
