-- ======================================================================
-- challenge — 챌린지와 멤버십 — 방 단위 기능 전부가 여기에 붙는다
--
-- 이 파일은 한 도메인의 표를 모아 둔다. 파일 안의 순서는 외래키 방향이고,
-- 파일 사이의 순서도 같다 — 앞 파일만 뒤 파일을 가리킨다.
-- ======================================================================

CREATE TABLE `challenges` (
  `id` binary(16) NOT NULL,
  `owner_id` binary(16) DEFAULT NULL,
  `owner_type` enum('USER','BOT') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER',
  `owner_granted_at` datetime(6) DEFAULT NULL,
  `owner_grant_reason` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '방장이 된 경위 — CREATE/TRANSFER/CLAIM. CLAIM 만 3일 면책 대상',
  `title` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `ai_title` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'AI 임시 제목(심사 중·거부 시 대체 표시, 서버가 draft에서 복사)',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `mode` enum('SOLO','GROUP') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `capacity` int DEFAULT NULL,
  `repeat_days` json NOT NULL,
  `weekly_count` tinyint NOT NULL DEFAULT '7' COMMENT '주간 수행 목표 횟수(1~7), FREQUENCY 일정',
  `duration_days` int DEFAULT NULL,
  `start_date` date NOT NULL,
  `end_date` date DEFAULT NULL,
  `template_id` bigint unsigned DEFAULT NULL,
  `verification_config` json NOT NULL,
  `params` json NOT NULL,
  `penalty_config` json NOT NULL,
  `reward_config` json NOT NULL,
  `anonymity` enum('REAL','ANONYMOUS') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'REAL',
  `status` enum('UPCOMING','ACTIVE','COMPLETED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UPCOMING',
  `moderation_status` enum('NONE','PENDING_REVIEW','APPROVED','REJECTED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NONE',
  `moderation_decided_at` datetime(6) DEFAULT NULL,
  `fix_deadline` datetime(6) DEFAULT NULL,
  `ai_assisted` tinyint(1) NOT NULL DEFAULT '0',
  `participant_count` int NOT NULL DEFAULT '0',
  `trending_score` double NOT NULL DEFAULT '0',
  `fail_count` int NOT NULL DEFAULT '0',
  `verification_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `deleted_at` datetime(6) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '0' COMMENT '설정 버전 — 수정·가입 등 충돌 감지(PATCH 낙관 잠금)',
  `min_tier` enum('BRONZE','SILVER','GOLD','DIAMOND','RUBY') COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '최소 입장 티어(표시 티어 기준) — 구 매너온도 게이트 대체',
  `visibility` enum('PUBLIC','PRIVATE') COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '그룹 공개 범위(솔로 NULL)',
  `ranking_visible` tinyint(1) DEFAULT NULL COMMENT '솔로 랭킹 노출 여부(그룹 NULL)',
  `param_specs` json DEFAULT NULL COMMENT '목표값 스펙 배열 [{key,value,defaultValue,kind,unit,min,max}] — 확인·수정 폼 복원용',
  `penalties` json DEFAULT NULL COMMENT '{score(자동=ON 고정), groupShare(그룹=ON 고정), watcher(선택)}',
  `moderation_title` enum('EXEMPT','APPROVED','IN_REVIEW','REJECTED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'EXEMPT' COMMENT '제목 심사 상태(AI 원본 미수정=EXEMPT)',
  `moderation_description` enum('NONE','EXEMPT','APPROVED','IN_REVIEW','REJECTED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'EXEMPT',
  `moderation_image` enum('NONE','APPROVED','IN_REVIEW','REJECTED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NONE' COMMENT '이미지 심사 상태(이미지 없음=NONE)',
  `moderation_locked_until` datetime(6) DEFAULT NULL COMMENT '반복 거부 수정 잠금 해제 시각(1시간 3회 거부 → 1시간)',
  `moderation_reject_count` int NOT NULL DEFAULT '0' COMMENT '현재 윈도우 내 거부 횟수',
  `moderation_reject_window_start` datetime(6) DEFAULT NULL COMMENT '거부 카운트 윈도우 시작(1시간 롤링)',
  `moderation_pending_since` datetime(6) DEFAULT NULL,
  `moderation_enqueued_at` datetime(6) DEFAULT NULL,
  `origin` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_challenge_id` binary(16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fkChallengeCreator` (`owner_id`),
  KEY `idx_challenge_explore` (`deleted_at`,`moderation_status`,`status`,`end_date`),
  KEY `idx_challenge_template` (`template_id`),
  KEY `ix_challenges_explore_candidate` (`mode`,`visibility`,`status`,`id`),
  KEY `idx_challenge_moderation_pending` (`moderation_enqueued_at`,`moderation_pending_since`,`id`),
  CONSTRAINT `fkChallengeCreator` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_challenge_owner_identity` CHECK ((((`owner_type` = _utf8mb4'USER') and (`owner_id` is not null)) or ((`owner_type` = _utf8mb4'BOT') and (`owner_id` is null)))),
  CONSTRAINT `ck_challenges_duration` CHECK ((`duration_days` >= 1)),
  CONSTRAINT `ck_challenges_weekly_count` CHECK ((`weekly_count` between 1 and 7))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_drafts` (
  `id` binary(16) NOT NULL COMMENT 'draftId(UUID)',
  `user_id` binary(16) NOT NULL,
  `origin` enum('AI','TEMPLATE','CLONE') COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '초안 출처 — 서버 기록, 클라 지정 불가',
  `source_challenge_id` binary(16) DEFAULT NULL COMMENT '복제 출처(CLONE 전용)',
  `template_id` bigint unsigned DEFAULT NULL COMMENT '루틴 템플릿(TEMPLATE·AI 매칭 시)',
  `title` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '원본 제목(심사 면제 대조 기준·AI 임시 제목)',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '원본 설명(대조 기준)',
  `payload` json NOT NULL COMMENT '초안 전 필드(draft 응답 스키마 그대로)',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `expires_at` datetime(6) NOT NULL COMMENT '생성 후 24시간 — 만료 건 일 배치 삭제',
  PRIMARY KEY (`id`),
  KEY `idx_challenge_drafts_user` (`user_id`),
  KEY `idx_challenge_drafts_expires` (`expires_at`),
  CONSTRAINT `fk_challenge_drafts_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_members` (
  `id` binary(16) NOT NULL,
  `challenge_id` binary(16) NOT NULL,
  `user_id` binary(16) NOT NULL,
  `role` enum('OWNER','MEMBER') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MEMBER',
  `status` enum('PENDING','ACTIVE','LEFT','REMOVED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `joined_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `schedule_type` enum('FIXED_DAYS','FREQUENCY') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'FIXED_DAYS',
  `target_days` int NOT NULL DEFAULT '0',
  `success_days` int NOT NULL DEFAULT '0',
  `fail_days` int NOT NULL DEFAULT '0',
  `progress_rate` decimal(5,2) NOT NULL DEFAULT '0.00',
  `today_status` enum('SUCCESS','PENDING','FAILED','NOT_TARGET','NOT_REQUIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_synced_at` datetime(6) DEFAULT NULL,
  `period_unit` enum('WEEK','MONTH') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `period_target` int DEFAULT NULL,
  `cur_period_start` date DEFAULT NULL,
  `cur_period_end` date DEFAULT NULL,
  `cur_period_completed` int DEFAULT NULL,
  `setup_status` enum('PENDING_SETUP','READY') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING_SETUP' COMMENT 'ìµœì´ˆ ì§„ìž… ì…‹ì—… ìƒíƒœ. READY ì „ê¹Œì§€ í‰ê°€ ìŠ¤í‚µ(Â§4)',
  `anchors` json DEFAULT NULL COMMENT 'ë©¤ë²„ GeoAnchor[] (PER_MEMBER). [{lat,lng,radiusM,label}] (Â§5)',
  `anchor_updated_at` datetime(6) DEFAULT NULL COMMENT 'ì•µì»¤ ë§ˆì§€ë§‰ ë³€ê²½ ì‹œê°(ìž¥ì†Œ ìˆ˜ì • ì¿¨ë‹¤ìš´ ê¸°ì¤€, Â§11.5)',
  `anchor_changed_at` datetime(6) DEFAULT NULL COMMENT '앵커 변경(PUT my-location) 시각 — 월 1회 한도 기준. 최초 셋업(POST setup)은 소진하지 않으므로 기록하지 않는다',
  `screen_apps` json DEFAULT NULL,
  `screen_apps_applied_from` datetime(6) DEFAULT NULL,
  `pending_screen_apps` json DEFAULT NULL,
  `pending_screen_apps_effective_date` date DEFAULT NULL,
  `screen_apps_updated_at` datetime(6) DEFAULT NULL,
  `screen_apps_changed_at` datetime(6) DEFAULT NULL COMMENT '대상 앱 변경(PUT my-screen-apps) 시각 — 월 1회 한도 기준. 최초 셋업은 소진하지 않는다',
  `fallback_used_period_start` date DEFAULT NULL COMMENT 'ì˜ˆë¹„ í´ë°± ì£¼1íšŒ(ë¡¤ë§ 7ì¼) ìœˆë„ìš° ì‹œìž‘ì¼(Â§9.2)',
  `fallback_used_count` int NOT NULL DEFAULT '0' COMMENT 'í˜„ìž¬ í´ë°± ìœˆë„ìš° ë‚´ ì‚¬ìš© íšŸìˆ˜(Â§9.2)',
  `ghost_pushed_at` datetime(6) DEFAULT NULL,
  `left_type` enum('LEAVE','KICK') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `left_at` datetime(6) DEFAULT NULL,
  `kick_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `kick_count` int NOT NULL DEFAULT '0',
  `rejoin_available_at` datetime(6) DEFAULT NULL,
  `rejoin_banned` tinyint(1) NOT NULL DEFAULT '0',
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '행이 마지막으로 바뀐 시각. 5분 보정이 「그 사이 움직인 방」을 찾는 입력',
  `leave_reason` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqMember` (`challenge_id`,`user_id`),
  KEY `ixMemberUserStatus` (`user_id`,`status`),
  KEY `ix_challenge_members_joined` (`joined_at`,`challenge_id`),
  KEY `ix_challenge_members_left` (`user_id`,`left_type`,`left_at` DESC),
  KEY `ix_member_challenge_status` (`challenge_id`,`status`),
  KEY `ix_member_updated_at` (`updated_at`),
  KEY `idx_member_user_leave` (`user_id`,`leave_reason`,`left_at` DESC),
  CONSTRAINT `fkMemberChallenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`),
  CONSTRAINT `fkMemberUser` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_member_history` (
  `challenge_id` binary(16) NOT NULL,
  `user_id` binary(16) NOT NULL,
  `final_role` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'OWNER/MANAGER/MEMBER — 삭제 시점 역할',
  `left_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'ACTIVE_AT_DELETE/LEFT/REMOVED — 이탈 경위',
  `left_at` datetime(6) DEFAULT NULL,
  `final_success_rate` decimal(5,2) DEFAULT NULL,
  `joined_at` datetime(6) DEFAULT NULL,
  `leave_reason` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `archived_at` datetime(6) DEFAULT NULL,
  `member_id` binary(16) DEFAULT NULL,
  `verification_snapshot` json DEFAULT NULL,
  PRIMARY KEY (`challenge_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_history` (
  `challenge_id` binary(16) NOT NULL,
  `title_snapshot` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `image_snapshot` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `start_date` date NOT NULL,
  `end_date` date DEFAULT NULL,
  `deleted_at` datetime(6) NOT NULL COMMENT '삭제 배치 수행 시각',
  `ai_title_snapshot` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `description_snapshot` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `owner_id_snapshot` binary(16) DEFAULT NULL,
  `owner_type_snapshot` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `mode` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `visibility` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `capacity` int DEFAULT NULL,
  `min_tier` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `weekly_count` int DEFAULT NULL,
  `verification_config` json DEFAULT NULL,
  `params` json DEFAULT NULL,
  `penalties` json DEFAULT NULL,
  `final_member_count` int DEFAULT NULL,
  `close_reason` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  `template_id` bigint DEFAULT NULL,
  `origin` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_challenge_id` binary(16) DEFAULT NULL,
  `repeat_days` json DEFAULT NULL,
  PRIMARY KEY (`challenge_id`),
  KEY `idx_challenge_history_deleted` (`deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_stats` (
  `challenge_id` binary(16) NOT NULL,
  `qualified_member_count` int NOT NULL DEFAULT '0' COMMENT '확정 판정 10회 이상인 현재 멤버 수 — 완주율의 분모',
  `qualified_success_member_count` int NOT NULL DEFAULT '0' COMMENT '그중 성공률 80% 이상인 멤버 수 — 완주율의 분자',
  `completion_rate` decimal(5,4) DEFAULT NULL COMMENT '완주율 0~1. 표본 미달·UPCOMING 이면 NULL(화면 미표시 + 해당 정렬에서 제외)',
  `total_progress_count` int NOT NULL DEFAULT '0' COMMENT '현재 멤버들의 확정 판정 누적 합 — 유지율 표본 조건',
  `non_failed_member_count` int NOT NULL DEFAULT '0' COMMENT '현재 멤버 중 확정 실패가 아닌 사람 수 — 유지율의 분자',
  `retention_rate` decimal(5,4) DEFAULT NULL COMMENT '유지율 0~1. 표본 미달·UPCOMING 이면 NULL',
  `recent_joins_24h` int NOT NULL DEFAULT '0' COMMENT '최근 24시간 신규 참여 수 — 인기 정렬 기준값, 1시간 배치',
  `last_joined_at_24h` datetime(6) DEFAULT NULL COMMENT '인기 동점 처리용 마지막 참여 시각',
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `popularity_updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`challenge_id`),
  KEY `ix_challenge_stats_completion` (`completion_rate` DESC,`challenge_id`),
  KEY `ix_challenge_stats_retention` (`retention_rate` DESC,`challenge_id`),
  KEY `ix_challenge_stats_popularity` (`recent_joins_24h` DESC,`last_joined_at_24h` DESC,`challenge_id`),
  CONSTRAINT `fk_challenge_stats_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_join_events` (
  `id` binary(16) NOT NULL,
  `challenge_id` binary(16) NOT NULL,
  `user_id` binary(16) NOT NULL,
  `joined_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '이 가입 사건이 일어난 시각. 재입장하면 새 줄이 쌓인다',
  PRIMARY KEY (`id`),
  KEY `ix_join_events_challenge_time` (`challenge_id`,`joined_at`),
  CONSTRAINT `fk_join_events_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `challenge_kicks` (
  `id` binary(16) NOT NULL,
  `challenge_id` binary(16) NOT NULL,
  `user_id` binary(16) NOT NULL,
  `reason` varchar(30) NOT NULL,
  `source_event_id` binary(16) NOT NULL,
  `evidence` json NOT NULL,
  `is_permanent` tinyint(1) NOT NULL,
  `kicked_at` datetime(6) NOT NULL,
  `rejoin_available_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_kick_idempotent` (`challenge_id`,`user_id`,`reason`,`source_event_id`),
  KEY `idx_kick_user` (`user_id`,`kicked_at` DESC),
  KEY `idx_kick_permanent` (`challenge_id`,`user_id`,`is_permanent`),
  CONSTRAINT `fk_kick_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `challenge_rejoin_backoffs` (
  `challenge_id` binary(16) NOT NULL,
  `user_id` binary(16) NOT NULL,
  `kick_count` int NOT NULL,
  `available_at` datetime(6) NOT NULL,
  PRIMARY KEY (`challenge_id`,`user_id`),
  KEY `fk_backoff_user` (`user_id`),
  CONSTRAINT `fk_backoff_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_backoff_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `challenge_delegations` (
  `id` binary(16) NOT NULL,
  `challenge_id` binary(16) NOT NULL,
  `requester_id` binary(16) NOT NULL,
  `target_user_id` binary(16) NOT NULL,
  `status` enum('PENDING','ACCEPTED','REJECTED','CANCELED','EXPIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `expires_at` datetime(6) NOT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixDelegationChallengeStatus` (`challenge_id`,`status`),
  KEY `ixDelegationTargetStatus` (`target_user_id`,`status`),
  KEY `fkDelegationRequester` (`requester_id`),
  CONSTRAINT `fkDelegationChallenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`),
  CONSTRAINT `fkDelegationRequester` FOREIGN KEY (`requester_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fkDelegationTarget` FOREIGN KEY (`target_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_invitations` (
  `id` binary(16) NOT NULL,
  `challenge_id` binary(16) NOT NULL,
  `inviter_id` binary(16) NOT NULL,
  `token_hash` binary(32) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_challenge_invitations_token` (`token_hash`),
  KEY `ix_challenge_invitations_challenge` (`challenge_id`,`expires_at`),
  KEY `fk_challenge_invitations_inviter` (`inviter_id`),
  CONSTRAINT `fk_challenge_invitations_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`),
  CONSTRAINT `fk_challenge_invitations_inviter` FOREIGN KEY (`inviter_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_image_uploads` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` binary(16) NOT NULL,
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `registered_at` datetime(6) DEFAULT NULL COMMENT '챌린지에 실제 등록된 시각(NULL=미등록 — 24h 후 정리 대상)',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_image_uploads_url` (`image_url`),
  KEY `idx_image_uploads_user` (`user_id`),
  KEY `idx_image_uploads_cleanup` (`registered_at`,`created_at`),
  CONSTRAINT `fk_image_uploads_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `user_challenge_counters` (
  `user_id` binary(16) NOT NULL,
  `active_join_count` int NOT NULL DEFAULT '0' COMMENT '현재 ACTIVE 참여 수 — 동시 3개 게이트의 락 대상',
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_user_challenge_counters_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `InviteCode` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqInviteCodeUser` (`userId`),
  UNIQUE KEY `uqInviteCodeCode` (`code`),
  CONSTRAINT `fkInviteCodeUser` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
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
  CONSTRAINT `fkInvitationSignupInvitee` FOREIGN KEY (`inviteeUserId`) REFERENCES `users` (`id`),
  CONSTRAINT `fkInvitationSignupInviter` FOREIGN KEY (`inviterUserId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
