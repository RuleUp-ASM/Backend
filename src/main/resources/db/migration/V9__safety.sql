-- ======================================================================
-- safety — 신고·차단·제재
--
-- 이 파일은 한 도메인의 표를 모아 둔다. 파일 안의 순서는 외래키 방향이고,
-- 파일 사이의 순서도 같다 — 앞 파일만 뒤 파일을 가리킨다.
-- ======================================================================

CREATE TABLE `reports` (
  `id` binary(16) NOT NULL,
  `reporter_id` binary(16) NOT NULL,
  `target_type` enum('USER','CHALLENGE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_id` binary(16) NOT NULL,
  `reason` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('PENDING','RESOLVED_NO_ACTION','RESOLVED_SANCTIONED','RESOLVED_MODERATION_REJECT') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `resolved_at` datetime(3) DEFAULT NULL COMMENT '운영자 종결 시각 — 처리 기한은 없다',
  `resolution_note` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `resolved_by` binary(16) DEFAULT NULL COMMENT '종결한 운영자',
  `behavior_violation` tinyint(1) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_reports_explore_exclusion` (`reporter_id`,`target_type`),
  KEY `ix_reports_behavior_threshold` (`behavior_violation`),
  KEY `ix_reports_status` (`status`,`created_at`),
  KEY `ix_reports_target` (`target_type`,`target_id`,`created_at`),
  CONSTRAINT `fk_reports_reporter` FOREIGN KEY (`reporter_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `report_snapshots` (
  `report_id` binary(16) NOT NULL,
  `payload` json NOT NULL COMMENT '대상 콘텐츠·이미지 키·수정 상태, 소속 챌린지, 피신고자 프로필, 발생 화면, 접수 시각',
  PRIMARY KEY (`report_id`),
  CONSTRAINT `fk_report_snapshots_report` FOREIGN KEY (`report_id`) REFERENCES `reports` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='신고 시점 스냅샷 — 갱신하지 않는다';

CREATE TABLE `user_blocks` (
  `blocker_id` binary(16) NOT NULL,
  `target_type` enum('USER','CHALLENGE') NOT NULL,
  `target_id` binary(16) NOT NULL,
  `blocked_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`blocker_id`,`target_type`,`target_id`),
  CONSTRAINT `fk_user_blocks_blocker` FOREIGN KEY (`blocker_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='개인 차단 — 신고자 본인 화면에만 적용되며 제재가 아니다';

CREATE TABLE `sanctions` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `user_id` binary(16) NOT NULL,
  `track` enum('AUTO','DISCRETIONARY') NOT NULL,
  `type` enum('FEATURE_SUSPENSION','LOCK','BAN') NOT NULL,
  `feature_code` varchar(30) DEFAULT NULL,
  `reason_code` varchar(40) NOT NULL,
  `reason_text` varchar(500) NOT NULL,
  `source` enum('REPORT','ANOMALY','DIRECT') NOT NULL,
  `source_id` binary(16) DEFAULT NULL,
  `starts_at` datetime(3) NOT NULL,
  `ends_at` datetime(3) DEFAULT NULL,
  `frozen_remaining_sec` int DEFAULT NULL,
  `revoked_at` datetime(3) DEFAULT NULL,
  `appeal_used` tinyint(1) NOT NULL DEFAULT '0',
  `operator_id` binary(16) DEFAULT NULL COMMENT '집행한 운영자 — AUTO 트랙은 NULL',
  `notified_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_sanctions_active` (`user_id`,`revoked_at`,`ends_at`),
  KEY `ix_sanctions_track` (`user_id`,`track`,`starts_at` DESC),
  KEY `ix_sanctions_source` (`source`,`source_id`),
  KEY `ix_sanctions_expiry` (`revoked_at`,`ends_at`,`id`),
  KEY `ix_sanctions_unnotified` (`notified_at`,`starts_at`),
  CONSTRAINT `fk_sanctions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `chk_sanctions_appeal_used` CHECK ((`appeal_used` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='제재 집행 이력 — 정지의 종류와 기간을 단독 소유. users.status 는 SUSPENDED 여부만 안다';

CREATE TABLE `ban_list` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `oauth_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'HMAC(salt, provider + ":" + subject)',
  `installation_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '소셜 계정을 바꿔 우회하는 경로를 막는 보조 차단',
  `sanction_id` binary(16) DEFAULT NULL COMMENT '근거가 된 제재 — 파기 후에도 남아야 해 FK 를 걸지 않는다',
  `banned_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ban_list_oauth` (`oauth_hash`),
  KEY `ix_ban_list_installation` (`installation_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='영구 정지 계정의 재가입 차단 — 솔트 해시만 보관';
