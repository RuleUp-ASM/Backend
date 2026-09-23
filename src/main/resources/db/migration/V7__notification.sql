-- ======================================================================
-- notification — 알림과 발송 큐
--
-- 이 파일은 한 도메인의 표를 모아 둔다. 파일 안의 순서는 외래키 방향이고,
-- 파일 사이의 순서도 같다 — 앞 파일만 뒤 파일을 가리킨다.
-- ======================================================================

CREATE TABLE `notifications` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `user_id` binary(16) NOT NULL,
  `tab` tinyint NOT NULL DEFAULT '0' COMMENT '0 알림 / 1 운영자 공지. 읽음 커서도 탭별로 따로',
  `type` varchar(40) NOT NULL,
  `toggle_group` varchar(10) NOT NULL DEFAULT 'ACCOUNT' COMMENT 'ACCOUNT/CHALLENGE/MARKETING/NONE — 발송 판정 입력 + 감사 스냅샷',
  `challenge_id` binary(16) DEFAULT NULL COMMENT '카운터 귀속 전용. 감시자 통지는 방 멤버가 아니라 NULL',
  `title` varchar(100) NOT NULL,
  `body` varchar(500) NOT NULL,
  `deeplink` varchar(255) DEFAULT NULL COMMENT '렌더링 완료된 문자열',
  `created_at` datetime(3) NOT NULL,
  `dedup_key` varchar(160) DEFAULT NULL COMMENT '발행 멱등(1회성). UNIQUE 가 INSERT 단계에서 막는다',
  `suppress_key` varchar(160) DEFAULT NULL COMMENT '인터벌 억제(반복성). 6개 타입만 사용, 나머지는 NULL',
  `pushed_at` datetime(3) DEFAULT NULL COMMENT '푸시 성공 시각. 억제 판정의 기준 — created_at 이 아니다',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_notifications_dedup` (`dedup_key`),
  KEY `idx_notifications_inbox` (`user_id`,`tab`,`id`),
  KEY `idx_notifications_suppress` (`user_id`,`suppress_key`,`pushed_at`),
  KEY `idx_notifications_purge` (`created_at`),
  CONSTRAINT `fk_notifications_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='알림함 적재 본체 — created_at 이 법적 고지 성립 시각';

CREATE TABLE `notification_mutes` (
  `user_id` binary(16) NOT NULL,
  `challenge_id` binary(16) NOT NULL,
  `muted_at` datetime(3) NOT NULL,
  PRIMARY KEY (`user_id`,`challenge_id`),
  KEY `fk_notification_mutes_challenge` (`challenge_id`),
  CONSTRAINT `fk_notification_mutes_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_notification_mutes_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='챌린지별 음소거';

CREATE TABLE `user_notification_settings` (
  `user_id` binary(16) NOT NULL,
  `push_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '마스터 — 가장 제한적인 것이 이긴다',
  `group_account` tinyint(1) NOT NULL DEFAULT '1',
  `group_challenge` tinyint(1) NOT NULL DEFAULT '1',
  `group_marketing` tinyint(1) NOT NULL DEFAULT '1' COMMENT '약관 수신 동의와 연동',
  `last_read_notification_id` binary(16) DEFAULT NULL COMMENT 'tab=0 읽음 지점. 서버는 카운트를 세지 않는다',
  `last_read_announcement_id` binary(16) DEFAULT NULL COMMENT 'tab=1 읽음 지점',
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_user_notification_settings_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='알림 설정 — 마스터 1 + 그룹 3 + 읽음 커서 2. 행이 없으면 전부 ON';

CREATE TABLE `PushOutbox` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `targetDate` date NOT NULL,
  `type` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `signalType` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('PENDING','SENT','SKIPPED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `scheduledAt` datetime(6) NOT NULL,
  `sentAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqPushOutbox` (`userId`,`challengeId`,`targetDate`,`type`),
  KEY `ixPushOutboxDue` (`status`,`scheduledAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `announcements` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `title` varchar(100) NOT NULL,
  `body` varchar(500) NOT NULL,
  `kind` varchar(20) NOT NULL DEFAULT 'MAINTENANCE' COMMENT 'MAINTENANCE / INCIDENT / TERMS / SHUTDOWN',
  `deep_link` varchar(200) DEFAULT NULL,
  `created_by` binary(16) NOT NULL COMMENT '발행한 운영자',
  `created_at` datetime(3) NOT NULL,
  `scheduled_at` datetime(3) DEFAULT NULL COMMENT 'null 이면 즉시. 미래면 그 시각 이후에 팬아웃된다',
  `canceled_at` datetime(3) DEFAULT NULL COMMENT '팬아웃 전 취소. 적재된 뒤에는 회수되지 않는다',
  `fanned_out_at` datetime(3) DEFAULT NULL,
  `recipient_count` int NOT NULL DEFAULT '0' COMMENT '실제로 적재된 행 수',
  PRIMARY KEY (`id`),
  KEY `ix_announcements_pending` (`fanned_out_at`,`canceled_at`,`scheduled_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='운영자 공지 원본 — 팬아웃은 잡이 한다';

CREATE TABLE `DeviceToken` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `token` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `platform` enum('ANDROID','IOS') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ANDROID',
  `isActive` tinyint(1) NOT NULL DEFAULT '1' COMMENT '0 이면 발송 대상에서 제외. 지우지 않고 남겨 CS 근거로 쓴다',
  `lastSeenAt` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqDeviceToken` (`token`),
  KEY `ixDeviceTokenUser` (`userId`),
  KEY `ixDeviceTokenActive` (`userId`,`isActive`),
  CONSTRAINT `fkDeviceTokenUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
