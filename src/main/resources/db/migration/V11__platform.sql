-- ======================================================================
-- platform — 도메인에 속하지 않는 기반 설비 — 아웃박스·멱등키·시스템 지표
--
-- 이 파일은 한 도메인의 표를 모아 둔다. 파일 안의 순서는 외래키 방향이고,
-- 파일 사이의 순서도 같다 — 앞 파일만 뒤 파일을 가리킨다.
-- ======================================================================

CREATE TABLE `outbox_messages` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `type` varchar(40) NOT NULL COMMENT 'NOTIFICATION / SANCTION_LEAVE ...',
  `payload` json NOT NULL,
  `dedup_key` varchar(160) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `available_at` datetime(3) NOT NULL,
  `processed_at` datetime(3) DEFAULT NULL,
  `dead_lettered_at` datetime(3) DEFAULT NULL COMMENT '재시도 상한을 넘겨 포기한 시각. 처리 완료(processed_at)와 구분한다 — 이 값이 있으면 발행되지 않았다',
  `attempts` int NOT NULL DEFAULT '0',
  `last_error` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_outbox_dedup` (`dedup_key`),
  KEY `ix_outbox_pending` (`processed_at`,`available_at`),
  KEY `ix_outbox_processed` (`processed_at`),
  KEY `ix_outbox_dead_lettered` (`dead_lettered_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='도메인 트랜잭션과 같은 커밋에 적는 발행 대기함';

CREATE TABLE `idempotency_keys` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` binary(16) NOT NULL,
  `idempotency_key` char(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '클라 발급 UUID — 확인 화면 진입 시 1회 생성',
  `request_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '요청 본문 SHA-256 — 동일 키+다른 본문 → 409',
  `response_snapshot` json DEFAULT NULL COMMENT '최초 201 응답 스냅샷 — 동일 키+동일 본문 재요청 시 재응답',
  `challenge_id` binary(16) DEFAULT NULL COMMENT '생성된 챌린지(성공 시)',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_idempotency_user_key` (`user_id`,`idempotency_key`),
  KEY `idx_idempotency_created` (`created_at`),
  CONSTRAINT `fk_idempotency_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `SystemMetricSnapshot` (
  `id` binary(16) NOT NULL,
  `capturedAt` datetime(6) NOT NULL,
  `cpuUserPct` decimal(5,2) DEFAULT NULL,
  `cpuSystemPct` decimal(5,2) DEFAULT NULL,
  `cpuIoWaitPct` decimal(5,2) DEFAULT NULL,
  `memUsedPct` decimal(5,2) DEFAULT NULL,
  `memUsedBytes` bigint DEFAULT NULL,
  `memTotalBytes` bigint DEFAULT NULL,
  `swapUsedBytes` bigint DEFAULT NULL,
  `swapTotalBytes` bigint DEFAULT NULL,
  `diskUsedPct` decimal(5,2) DEFAULT NULL,
  `diskFreeBytes` bigint DEFAULT NULL,
  `diskTotalBytes` bigint DEFAULT NULL,
  `diskReadsPerSec` decimal(12,2) DEFAULT NULL,
  `diskWritesPerSec` decimal(12,2) DEFAULT NULL,
  `netInBytesPerSec` decimal(16,2) DEFAULT NULL,
  `netOutBytesPerSec` decimal(16,2) DEFAULT NULL,
  `tcpConnEstablished` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ixSystemMetricCapturedAt` (`capturedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
