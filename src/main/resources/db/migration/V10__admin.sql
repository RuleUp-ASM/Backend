-- ======================================================================
-- admin — 운영 콘솔과 CS
--
-- 이 파일은 한 도메인의 표를 모아 둔다. 파일 안의 순서는 외래키 방향이고,
-- 파일 사이의 순서도 같다 — 앞 파일만 뒤 파일을 가리킨다.
-- ======================================================================

CREATE TABLE `admin_audit_logs` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `operator_id` binary(16) DEFAULT NULL,
  `action` varchar(40) NOT NULL COMMENT 'SNAPSHOT_VIEW 는 개인정보 열람이라 별도 action',
  `target_type` varchar(20) DEFAULT NULL COMMENT 'USER / CHALLENGE / REPORT',
  `target_id` binary(16) DEFAULT NULL,
  `result` varchar(10) NOT NULL COMMENT 'ALLOWED / DENIED',
  `payload_digest` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `occurred_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_audit_operator` (`operator_id`,`occurred_at` DESC),
  KEY `ix_audit_target` (`target_type`,`target_id`,`occurred_at` DESC),
  KEY `ix_audit_denied` (`result`,`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='운영 조작 이력(append only) — 조회도 기록한다';

CREATE TABLE `inquiries` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `user_id` binary(16) NOT NULL,
  `category` varchar(30) NOT NULL COMMENT '현재 분류 — 운영자가 바꿀 수 있다',
  `origin_category` varchar(30) NOT NULL COMMENT '유저가 처음 고른 분류',
  `body` varchar(1000) NOT NULL COMMENT '10~1,000자',
  `image_urls` json DEFAULT NULL,
  `app_version` varchar(20) DEFAULT NULL,
  `os_version` varchar(30) DEFAULT NULL,
  `device_model` varchar(60) DEFAULT NULL,
  `error_log_id` varchar(64) DEFAULT NULL COMMENT '최근 오류 로그 ID — 오류 문의의 1차 대조 키',
  `status` enum('RECEIVED','ANSWERED') NOT NULL DEFAULT 'RECEIVED',
  `answer_text` varchar(2000) DEFAULT NULL,
  `answered_at` datetime(3) DEFAULT NULL,
  `answered_by` binary(16) DEFAULT NULL COMMENT '답변한 운영자',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_inquiries_queue` (`status`,`created_at`),
  KEY `ix_inquiries_user` (`user_id`,`created_at`),
  KEY `ix_inquiries_category` (`origin_category`,`created_at`),
  CONSTRAINT `fk_inquiries_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CS 문의 — 답변 등록이 곧 종결이며 재문의 경로가 없다';
