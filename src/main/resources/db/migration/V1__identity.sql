-- ======================================================================
-- identity — 계정·인증·약관 — 모든 도메인이 users 를 가리키므로 가장 먼저 선다
--
-- 이 파일은 한 도메인의 표를 모아 둔다. 파일 안의 순서는 외래키 방향이고,
-- 파일 사이의 순서도 같다 — 앞 파일만 뒤 파일을 가리킨다.
-- ======================================================================

CREATE TABLE `users` (
  `id` binary(16) NOT NULL COMMENT '애플리케이션에서 생성한 UUIDv7',
  `oauth_provider` enum('KAKAO','GOOGLE','APPLE','NAVER') NOT NULL,
  `oauth_subject` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `status` enum('ACTIVE','SUSPENDED','WITHDRAWN') NOT NULL DEFAULT 'ACTIVE' COMMENT '3종. 정지의 종류·기간은 sanctions 가 소유하며 게이트는 SUSPENDED 일 때만 조회한다',
  `role` enum('MEMBER','OPERATOR') NOT NULL DEFAULT 'MEMBER' COMMENT '백오피스 접근 롤 — 개인정보 열람 권한이 붙으므로 부여 자체가 운영 결정이다',
  `status_before_withdrawal` enum('ACTIVE','SUSPENDED','WITHDRAWN') DEFAULT NULL COMMENT '탈퇴 직전 상태 — 복원 시 제재가 남아 있으면 SUSPENDED 로 되돌린다',
  `nickname` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `approved_nickname` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `nickname_status` enum('PENDING','APPROVED','REJECTED','CONFLICT') NOT NULL DEFAULT 'PENDING',
  `nickname_changed_at` datetime(3) DEFAULT NULL,
  `profile_image_key` varchar(512) DEFAULT NULL,
  `profile_image_status` enum('NONE','PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'NONE',
  `installation_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `active_installation_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS ((case when (`status` <> _utf8mb4'WITHDRAWN') then `installation_id` else NULL end)) STORED,
  `device_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `country_code` char(2) DEFAULT NULL,
  `platform` enum('ANDROID','IOS') DEFAULT NULL,
  `app_version_code` int DEFAULT NULL,
  `app_version_name` varchar(32) DEFAULT NULL,
  `os_version` varchar(32) DEFAULT NULL,
  `sdk_int` int DEFAULT NULL,
  `device_model` varchar(64) DEFAULT NULL,
  `manufacturer` varchar(64) DEFAULT NULL,
  `low_ram` tinyint(1) DEFAULT NULL,
  `device_info_updated_at` datetime(3) DEFAULT NULL,
  `last_login_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_active_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '인증 API 호출 시 하루 한 번 갱신',
  `deleted_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `active_requested_nickname` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS ((case when ((`status` <> _utf8mb4'WITHDRAWN') and (`nickname_status` = _utf8mb4'PENDING')) then `nickname` else NULL end)) STORED,
  `active_approved_nickname` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS ((case when (`status` <> _utf8mb4'WITHDRAWN') then `approved_nickname` else NULL end)) STORED,
  `profile_changed_at` datetime(3) DEFAULT NULL COMMENT '닉네임·사진 통합 잠금 시작 시각. +1개월이 해제일, +10분이 같은 저장 세션 경계',
  `ram_mb` int DEFAULT NULL,
  `profile_image_registered_at` datetime(6) DEFAULT NULL,
  `profile_save_kind` varchar(10) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_users_oauth_identity` (`oauth_provider`,`oauth_subject`),
  UNIQUE KEY `uq_users_active_requested_nickname` (`active_requested_nickname`),
  UNIQUE KEY `uq_users_active_approved_nickname` (`active_approved_nickname`),
  UNIQUE KEY `uq_users_active_installation_id` (`active_installation_id`),
  KEY `idx_users_device_id` (`device_id`,`id`),
  KEY `idx_users_installation_withdrawn` (`installation_id`,`deleted_at`),
  KEY `idx_users_nickname_pending` (`nickname_status`,`id`),
  KEY `idx_users_image_pending` (`profile_image_status`,`id`),
  CONSTRAINT `chk_users_oauth_subject` CHECK (((`oauth_subject` is not null) or (`status` = _utf8mb4'WITHDRAWN'))),
  CONSTRAINT `chk_users_withdrawal` CHECK ((((`status` = _utf8mb4'WITHDRAWN') and (`deleted_at` is not null)) or ((`status` <> _utf8mb4'WITHDRAWN') and (`deleted_at` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사용자 계정 코어';

CREATE TABLE `user_information` (
  `user_id` binary(16) NOT NULL,
  `birth_date` date DEFAULT NULL,
  `gender` enum('MALE','FEMALE','NON_BINARY','PREFER_NOT_TO_SAY') DEFAULT NULL,
  `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_user_information_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사용자 개인정보';

CREATE TABLE `user_interests` (
  `user_id` binary(16) NOT NULL,
  `category` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`,`category`),
  KEY `idx_user_interests_category` (`category`,`user_id`),
  CONSTRAINT `fk_user_interests_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사용자 관심 카테고리';

CREATE TABLE `social_tokens` (
  `user_id` binary(16) NOT NULL,
  `provider` enum('KAKAO','GOOGLE','APPLE','NAVER') NOT NULL,
  `access_token_enc` varbinary(2048) NOT NULL,
  `refresh_token_enc` varbinary(2048) DEFAULT NULL,
  `encryption_key_version` smallint unsigned NOT NULL,
  `expires_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`,`provider`),
  KEY `idx_social_tokens_expiry` (`provider`,`expires_at`,`user_id`),
  CONSTRAINT `fk_social_tokens_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='외부 소셜 제공자 토큰';

CREATE TABLE `refresh_tokens` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `user_id` binary(16) NOT NULL,
  `family_id` binary(16) NOT NULL,
  `parent_token_id` binary(16) DEFAULT NULL,
  `token_hash` binary(32) NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  `revoked_at` datetime(3) DEFAULT NULL,
  `reuse_detected_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_refresh_tokens_hash` (`token_hash`),
  KEY `fk_refresh_tokens_parent` (`parent_token_id`),
  KEY `idx_refresh_tokens_user_active` (`user_id`,`revoked_at`,`expires_at`),
  KEY `idx_refresh_tokens_family_active` (`family_id`,`revoked_at`,`expires_at`),
  KEY `idx_refresh_tokens_expiry` (`expires_at`,`id`),
  KEY `idx_refresh_tokens_cleanup` (`reuse_detected_at`,`revoked_at`,`expires_at`,`id`),
  CONSTRAINT `fk_refresh_tokens_parent` FOREIGN KEY (`parent_token_id`) REFERENCES `refresh_tokens` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_refresh_tokens_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_refresh_tokens_reuse` CHECK (((`reuse_detected_at` is null) or (`revoked_at` is not null)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Refresh Token 회전 및 폐기 정보';

CREATE TABLE `signup_token_consumptions` (
  `jti` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  PRIMARY KEY (`jti`),
  KEY `idx_signup_consumption_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_agreement_states` (
  `user_id` binary(16) NOT NULL,
  `agreement_type` enum('TOS','PRIVACY','LOCATION','MARKETING','EVENT','LOCATION_INFO','HEALTH_INFO') NOT NULL,
  `agreed` tinyint(1) NOT NULL,
  `version` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `agreed_at` datetime(3) NOT NULL,
  PRIMARY KEY (`user_id`,`agreement_type`),
  CONSTRAINT `fk_user_agreement_states_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_user_agreement_states_agreed` CHECK ((`agreed` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='동의 현재 상태 — 유저당 최대 7행 고정, UPSERT 로 덮어씀';

CREATE TABLE `user_agreement_events` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `user_id` binary(16) NOT NULL,
  `agreement_type` enum('TOS','PRIVACY','LOCATION','MARKETING','EVENT','LOCATION_INFO','HEALTH_INFO') NOT NULL COMMENT '약관 5종 + 법정 개별 동의 2종. 구 NIGHT_PUSH 폐기(2026-08-28)',
  `agreed` tinyint(1) NOT NULL,
  `version` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_agreements_latest` (`user_id`,`agreement_type`,`created_at` DESC,`id` DESC),
  CONSTRAINT `fk_user_agreements_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_user_agreements_agreed` CHECK ((`agreed` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='동의·철회 이력(append-only) — 입증 책임의 근거';

CREATE TABLE `user_activity` (
  `user_id` binary(16) NOT NULL,
  `last_active_on` date NOT NULL,
  `notified_stage` varchar(20) NOT NULL DEFAULT 'NONE',
  PRIMARY KEY (`user_id`),
  KEY `idx_activity_sweep` (`notified_stage`,`last_active_on`,`user_id`),
  CONSTRAINT `fk_user_activity_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
