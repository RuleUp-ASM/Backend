-- 챌린지 생성 및 라이프사이클 스펙(7.20) 정합화.
--  1) status ENUM RECRUITING → UPCOMING
--  2) moderationStatus ENUM 에 NONE 추가(이미지 없음=즉시 모집), 기존 이미지 없는 APPROVED 를 NONE 으로 정렬
--  3) maxParticipants(정원) 컬럼 추가 + 백필(SOLO=1, GROUP=기존 참여자 수와 100 중 큰 값)
--  4) MemberRole ENUM 에 MANAGER(공동 관리자) 추가
--  5) 방장 위임 요청 테이블(ChallengeDelegation) 신설

-- 1) lifecycle status: RECRUITING → UPCOMING (값 재명명)
ALTER TABLE `Challenge`
    MODIFY `status` enum('RECRUITING','UPCOMING','ACTIVE','COMPLETED')
        COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UPCOMING';
UPDATE `Challenge` SET `status` = 'UPCOMING' WHERE `status` = 'RECRUITING';
ALTER TABLE `Challenge`
    MODIFY `status` enum('UPCOMING','ACTIVE','COMPLETED')
        COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UPCOMING';

-- 2) moderationStatus: NONE 추가 + 이미지 없는 기존 APPROVED 정렬
ALTER TABLE `Challenge`
    MODIFY `moderationStatus` enum('NONE','PENDING_REVIEW','APPROVED','REJECTED')
        COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NONE';
UPDATE `Challenge`
    SET `moderationStatus` = 'NONE'
    WHERE (`imageUrl` IS NULL OR `imageUrl` = '') AND `moderationStatus` = 'APPROVED';

-- 3) 최대 참여 인원(정원)
ALTER TABLE `Challenge`
    ADD COLUMN `maxParticipants` int DEFAULT NULL AFTER `minMannerTemperature`;
UPDATE `Challenge`
    SET `maxParticipants` = CASE
        WHEN `participationType` = 'SOLO' THEN 1
        ELSE GREATEST(`participantCount`, 100)
    END;

-- 4) 공동 관리자 역할
ALTER TABLE `ChallengeMember`
    MODIFY `role` enum('OWNER','MANAGER','MEMBER')
        COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MEMBER';

-- 5) 방장 위임 요청
CREATE TABLE `ChallengeDelegation` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `requesterId` binary(16) NOT NULL,
  `targetUserId` binary(16) NOT NULL,
  `status` enum('PENDING','ACCEPTED','REJECTED','CANCELED','EXPIRED')
      COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `expiresAt` datetime(6) NOT NULL,
  `resolvedAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixDelegationChallengeStatus` (`challengeId`,`status`),
  KEY `ixDelegationTargetStatus` (`targetUserId`,`status`),
  CONSTRAINT `fkDelegationChallenge` FOREIGN KEY (`challengeId`) REFERENCES `Challenge` (`id`),
  CONSTRAINT `fkDelegationRequester` FOREIGN KEY (`requesterId`) REFERENCES `User` (`id`),
  CONSTRAINT `fkDelegationTarget` FOREIGN KEY (`targetUserId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
