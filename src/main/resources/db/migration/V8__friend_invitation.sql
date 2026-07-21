-- 마이프로필: 친구 초대(referral).
--  1) InviteCode(유저당 1개 초대 코드) — 멱등 생성
--  2) InvitationSignup(피초대자 가입 기록) — 초대 현황

CREATE TABLE `InviteCode` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `code` varchar(6) COLLATE utf8mb4_unicode_ci NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqInviteCodeUser` (`userId`),
  UNIQUE KEY `uqInviteCodeCode` (`code`),
  CONSTRAINT `fkInviteCodeUser` FOREIGN KEY (`userId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `InvitationSignup` (
  `id` binary(16) NOT NULL,
  `inviterUserId` binary(16) NOT NULL,
  `inviteeUserId` binary(16) NOT NULL,
  `occurredAt` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqInvitationSignupInvitee` (`inviteeUserId`),
  KEY `ixInvitationSignupInviter` (`inviterUserId`,`occurredAt`),
  CONSTRAINT `fkInvitationSignupInviter` FOREIGN KEY (`inviterUserId`) REFERENCES `User` (`id`),
  CONSTRAINT `fkInvitationSignupInvitee` FOREIGN KEY (`inviteeUserId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
