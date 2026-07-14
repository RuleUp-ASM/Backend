-- 고스트(무음) 푸시 — FCM 전송 파이프라인.
--  · DeviceToken : 유저별 FCM 등록 토큰(멀티 디바이스). 전송 대상 조회원.
--  · PushOutbox  : 실시간 권한공백(§8.5) 트리거 큐. 적재(PENDING) → 스윕 발송(SENT/SKIPPED).
-- 스타일: V1 baseline과 동일(binary(16), InnoDB, utf8mb4, CamelCase).

CREATE TABLE `DeviceToken` (
  `id`         binary(16) NOT NULL,
  `userId`     binary(16) NOT NULL,
  `token`      varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `platform`   enum('ANDROID','IOS') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ANDROID',
  `lastSeenAt` datetime(6) NOT NULL,
  `createdAt`  datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqDeviceToken` (`token`),
  KEY `ixDeviceTokenUser` (`userId`),
  CONSTRAINT `fkDeviceTokenUser` FOREIGN KEY (`userId`) REFERENCES `User` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `PushOutbox` (
  `id`          binary(16) NOT NULL,
  `userId`      binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `targetDate`  date NOT NULL,
  `type`        varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `signalType`  varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status`      enum('PENDING','SENT','SKIPPED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `scheduledAt` datetime(6) NOT NULL,
  `sentAt`      datetime(6) DEFAULT NULL,
  `createdAt`   datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  -- 멱등: 유저×챌린지×날짜×타입 하루 1건(sync 마다 중복 적재 차단).
  UNIQUE KEY `uqPushOutbox` (`userId`,`challengeId`,`targetDate`,`type`),
  -- 스윕 클레임(status='PENDING' AND scheduledAt<=now ORDER BY scheduledAt).
  KEY `ixPushOutboxDue` (`status`,`scheduledAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
