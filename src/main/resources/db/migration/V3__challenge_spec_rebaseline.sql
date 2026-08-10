-- =====================================================================
-- V3: 챌린지 생성·라이프사이클 스펙 재정합 (백엔드 테크 스펙 4-1)
--  1) 챌린지 도메인 테이블 snake_case 전면 전환 (RENAME TABLE/COLUMN — 메타데이터 변경, FK 자동 유지)
--  2) challenges 신규 컬럼 — 신규 계약(ai_title·version·min_tier·visibility·ranking_visible·
--     param_specs·penalties·항목별 모더레이션 + 반복 거부 잠금)
--  3) 신규 테이블 — challenge_drafts(초안 24h 보관·원본 대조) / idempotency_keys(생성 멱등) /
--     이력 3종(하드 삭제 후 완료 기록·최종 랭킹 열람) / challenge_image_uploads(업로드 소유 검증)
--  구 컬럼(min_manner_temperature·anonymity·moderation_status 등)은 expand 단계로 유지 —
--  가입 게이트·심사 개편이 코드 전환을 마친 뒤 릴리즈에서 contract(드랍)한다.
-- =====================================================================

-- 1) 테이블명 snake_case 전환
RENAME TABLE `Challenge` TO `challenges`,
             `ChallengeMember` TO `challenge_members`,
             `ChallengeDelegation` TO `challenge_delegations`;

-- 2) challenges 컬럼 snake_case 전환
-- 체크 제약이 컬럼을 참조하면 RENAME COLUMN 이 거부되므로 먼저 드랍하고, 전환 후 새 이름으로 재생성한다.
ALTER TABLE `challenges`
    DROP CHECK `ckChallengeDuration`,
    DROP CHECK `ckChallengeMinManner`;

ALTER TABLE `challenges`
    RENAME COLUMN `creatorId` TO `owner_id`,
    RENAME COLUMN `imageUrl` TO `image_url`,
    RENAME COLUMN `participationType` TO `mode`,
    RENAME COLUMN `minMannerTemperature` TO `min_manner_temperature`,
    RENAME COLUMN `maxParticipants` TO `capacity`,
    RENAME COLUMN `repeatDays` TO `repeat_days`,
    RENAME COLUMN `durationDays` TO `duration_days`,
    RENAME COLUMN `startDate` TO `start_date`,
    RENAME COLUMN `endDate` TO `end_date`,
    RENAME COLUMN `templateId` TO `template_id`,
    RENAME COLUMN `verificationConfig` TO `verification_config`,
    RENAME COLUMN `penaltyConfig` TO `penalty_config`,
    RENAME COLUMN `rewardConfig` TO `reward_config`,
    RENAME COLUMN `moderationStatus` TO `moderation_status`,
    RENAME COLUMN `moderationDecidedAt` TO `moderation_decided_at`,
    RENAME COLUMN `fixDeadline` TO `fix_deadline`,
    RENAME COLUMN `aiAssisted` TO `ai_assisted`,
    RENAME COLUMN `participantCount` TO `participant_count`,
    RENAME COLUMN `trendingScore` TO `trending_score`,
    RENAME COLUMN `failCount` TO `fail_count`,
    RENAME COLUMN `verificationType` TO `verification_type`,
    RENAME COLUMN `createdAt` TO `created_at`,
    RENAME COLUMN `updatedAt` TO `updated_at`,
    RENAME COLUMN `deletedAt` TO `deleted_at`;

ALTER TABLE `challenges`
    ADD CONSTRAINT `ck_challenges_duration` CHECK (`duration_days` >= 1),
    ADD CONSTRAINT `ck_challenges_min_manner` CHECK (`min_manner_temperature` IS NULL OR `min_manner_temperature` >= 0.0);

-- challenges 신규 컬럼 (신규 계약)
ALTER TABLE `challenges`
    ADD COLUMN `ai_title` varchar(30) NULL COMMENT 'AI 임시 제목(심사 중·거부 시 대체 표시, 서버가 draft에서 복사)' AFTER `title`,
    ADD COLUMN `version` int NOT NULL DEFAULT 0 COMMENT '설정 버전 — 수정·가입 등 충돌 감지(PATCH 낙관 잠금)',
    ADD COLUMN `min_tier` enum('BRONZE','SILVER','GOLD','DIAMOND','RUBY') NULL COMMENT '최소 입장 티어(표시 티어 기준) — 구 매너온도 게이트 대체',
    ADD COLUMN `visibility` enum('PUBLIC','PRIVATE') NULL COMMENT '그룹 공개 범위(솔로 NULL)',
    ADD COLUMN `ranking_visible` tinyint(1) NULL COMMENT '솔로 랭킹 노출 여부(그룹 NULL)',
    ADD COLUMN `param_specs` json NULL COMMENT '목표값 스펙 배열 [{key,value,defaultValue,kind,unit,min,max}] — 확인·수정 폼 복원용',
    ADD COLUMN `penalties` json NULL COMMENT '{score(자동=ON 고정), groupShare(그룹=ON 고정), watcher(선택)}',
    ADD COLUMN `moderation_title` enum('EXEMPT','APPROVED','IN_REVIEW','REJECTED') NOT NULL DEFAULT 'EXEMPT' COMMENT '제목 심사 상태(AI 원본 미수정=EXEMPT)',
    ADD COLUMN `moderation_description` enum('EXEMPT','APPROVED','IN_REVIEW','REJECTED') NOT NULL DEFAULT 'EXEMPT' COMMENT '설명 심사 상태',
    ADD COLUMN `moderation_image` enum('NONE','APPROVED','IN_REVIEW','REJECTED') NOT NULL DEFAULT 'NONE' COMMENT '이미지 심사 상태(이미지 없음=NONE)',
    ADD COLUMN `moderation_locked_until` datetime(6) NULL COMMENT '반복 거부 수정 잠금 해제 시각(1시간 3회 거부 → 1시간)',
    ADD COLUMN `moderation_reject_count` int NOT NULL DEFAULT 0 COMMENT '현재 윈도우 내 거부 횟수',
    ADD COLUMN `moderation_reject_window_start` datetime(6) NULL COMMENT '거부 카운트 윈도우 시작(1시간 롤링)';

-- 기존 행 정합: 대체 표시용 ai_title 은 현재 제목으로, 공개 범위·랭킹 노출은 유형별 기본값으로,
-- 이미지 심사 상태는 구 단일 moderation_status 에서 이관한다.
UPDATE `challenges` SET `ai_title` = `title` WHERE `ai_title` IS NULL;
UPDATE `challenges` SET `visibility` = 'PUBLIC' WHERE `mode` = 'GROUP' AND `visibility` IS NULL;
UPDATE `challenges` SET `ranking_visible` = 1 WHERE `mode` = 'SOLO' AND `ranking_visible` IS NULL;
UPDATE `challenges` SET `moderation_image` =
    CASE `moderation_status`
        WHEN 'PENDING_REVIEW' THEN 'IN_REVIEW'
        WHEN 'APPROVED'       THEN 'APPROVED'
        WHEN 'REJECTED'       THEN 'REJECTED'
        ELSE 'NONE'
    END;

-- 3) challenge_members 컬럼 snake_case 전환
ALTER TABLE `challenge_members`
    RENAME COLUMN `challengeId` TO `challenge_id`,
    RENAME COLUMN `userId` TO `user_id`,
    RENAME COLUMN `joinedAt` TO `joined_at`,
    RENAME COLUMN `scheduleType` TO `schedule_type`,
    RENAME COLUMN `targetDays` TO `target_days`,
    RENAME COLUMN `successDays` TO `success_days`,
    RENAME COLUMN `failDays` TO `fail_days`,
    RENAME COLUMN `progressRate` TO `progress_rate`,
    RENAME COLUMN `todayStatus` TO `today_status`,
    RENAME COLUMN `lastSyncedAt` TO `last_synced_at`,
    RENAME COLUMN `periodUnit` TO `period_unit`,
    RENAME COLUMN `periodTarget` TO `period_target`,
    RENAME COLUMN `curPeriodStart` TO `cur_period_start`,
    RENAME COLUMN `curPeriodEnd` TO `cur_period_end`,
    RENAME COLUMN `curPeriodCompleted` TO `cur_period_completed`,
    RENAME COLUMN `setupStatus` TO `setup_status`,
    RENAME COLUMN `anchorUpdatedAt` TO `anchor_updated_at`,
    RENAME COLUMN `screenApps` TO `screen_apps`,
    RENAME COLUMN `screenAppsAppliedFrom` TO `screen_apps_applied_from`,
    RENAME COLUMN `pendingScreenApps` TO `pending_screen_apps`,
    RENAME COLUMN `pendingScreenAppsEffectiveDate` TO `pending_screen_apps_effective_date`,
    RENAME COLUMN `screenAppsUpdatedAt` TO `screen_apps_updated_at`,
    RENAME COLUMN `fallbackUsedPeriodStart` TO `fallback_used_period_start`,
    RENAME COLUMN `fallbackUsedCount` TO `fallback_used_count`,
    RENAME COLUMN `ghostPushedAt` TO `ghost_pushed_at`;

-- 4) challenge_delegations 컬럼 snake_case 전환
ALTER TABLE `challenge_delegations`
    RENAME COLUMN `challengeId` TO `challenge_id`,
    RENAME COLUMN `requesterId` TO `requester_id`,
    RENAME COLUMN `targetUserId` TO `target_user_id`,
    RENAME COLUMN `expiresAt` TO `expires_at`,
    RENAME COLUMN `resolvedAt` TO `resolved_at`,
    RENAME COLUMN `createdAt` TO `created_at`,
    RENAME COLUMN `updatedAt` TO `updated_at`;

-- 5) challenge_drafts — 초안 원본 24시간 보관 (draftId 원본 대조·심사 면제 판정·출처 추적)
CREATE TABLE `challenge_drafts` (
    `id`                  binary(16)   NOT NULL COMMENT 'draftId(UUID)',
    `user_id`             binary(16)   NOT NULL,
    `origin`              enum('AI','TEMPLATE','CLONE') NOT NULL COMMENT '초안 출처 — 서버 기록, 클라 지정 불가',
    `source_challenge_id` binary(16)   NULL COMMENT '복제 출처(CLONE 전용)',
    `template_id`         bigint unsigned NULL COMMENT '루틴 템플릿(TEMPLATE·AI 매칭 시)',
    `title`               varchar(30)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '원본 제목(심사 면제 대조 기준·AI 임시 제목)',
    `description`         varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '원본 설명(대조 기준)',
    `payload`             json         NOT NULL COMMENT '초안 전 필드(draft 응답 스키마 그대로)',
    `created_at`          datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `expires_at`          datetime(6)  NOT NULL COMMENT '생성 후 24시간 — 만료 건 일 배치 삭제',
    PRIMARY KEY (`id`),
    KEY `idx_challenge_drafts_user` (`user_id`),
    KEY `idx_challenge_drafts_expires` (`expires_at`),
    CONSTRAINT `fk_challenge_drafts_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6) idempotency_keys — 생성 API 멱등 보장 (DB 보관 — 재배포 시 유실되는 인메모리 금지)
CREATE TABLE `idempotency_keys` (
    `id`                bigint       NOT NULL AUTO_INCREMENT,
    `user_id`           binary(16)   NOT NULL,
    `idempotency_key`   char(36)     NOT NULL COMMENT '클라 발급 UUID — 확인 화면 진입 시 1회 생성',
    `request_hash`      char(64)     NOT NULL COMMENT '요청 본문 SHA-256 — 동일 키+다른 본문 → 409',
    `response_snapshot` json         NULL COMMENT '최초 201 응답 스냅샷 — 동일 키+동일 본문 재요청 시 재응답',
    `challenge_id`      binary(16)   NULL COMMENT '생성된 챌린지(성공 시)',
    `created_at`        datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_idempotency_user_key` (`user_id`, `idempotency_key`),
    KEY `idx_idempotency_created` (`created_at`),
    CONSTRAINT `fk_idempotency_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7) 이력 테이블 3종 — 하드 삭제 직전 스냅샷 (완료 목록·최종 랭킹·인증 원본 소프트 참조의 근거)
CREATE TABLE `challenge_history` (
    `challenge_id`   binary(16)   NOT NULL,
    `title_snapshot` varchar(30)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    `image_snapshot` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    `category`       varchar(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    `start_date`     date         NOT NULL,
    `end_date`       date         NOT NULL,
    `deleted_at`     datetime(6)  NOT NULL COMMENT '삭제 배치 수행 시각',
    PRIMARY KEY (`challenge_id`),
    KEY `idx_challenge_history_deleted` (`deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_member_history` (
    `challenge_id`       binary(16)  NOT NULL,
    `user_id`            binary(16)  NOT NULL,
    `final_role`         varchar(10) NOT NULL COMMENT 'OWNER/MANAGER/MEMBER — 삭제 시점 역할',
    `left_type`          varchar(20) NOT NULL COMMENT 'ACTIVE_AT_DELETE/LEFT/REMOVED — 이탈 경위',
    `left_at`            datetime(6) NULL,
    `final_success_rate` decimal(5,2) NULL,
    PRIMARY KEY (`challenge_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `challenge_final_ranking` (
    `challenge_id`   binary(16) NOT NULL,
    `user_id`        binary(16) NOT NULL,
    `rank_no`        int        NOT NULL,
    `score_snapshot` decimal(5,2) NULL COMMENT '순위 산정 기준값(성공률) 스냅샷',
    PRIMARY KEY (`challenge_id`, `user_id`),
    KEY `idx_final_ranking_rank` (`challenge_id`, `rank_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8) challenge_image_uploads — 업로드 소유 검증(임의 외부 URL·타인 객체 거절) + 미등록 24h 정리
CREATE TABLE `challenge_image_uploads` (
    `id`            bigint       NOT NULL AUTO_INCREMENT,
    `user_id`       binary(16)   NOT NULL,
    `image_url`     varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    `registered_at` datetime(6)  NULL COMMENT '챌린지에 실제 등록된 시각(NULL=미등록 — 24h 후 정리 대상)',
    `created_at`    datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_image_uploads_url` (`image_url`),
    KEY `idx_image_uploads_user` (`user_id`),
    KEY `idx_image_uploads_cleanup` (`registered_at`, `created_at`),
    CONSTRAINT `fk_image_uploads_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
