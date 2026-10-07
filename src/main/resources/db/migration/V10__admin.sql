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
  `answer_read_at` datetime(3) DEFAULT NULL COMMENT '유저가 답변을 처음 열어 본 시각 — NULL 이고 답변돼 있으면 새 답변',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_inquiries_queue` (`status`,`created_at`),
  KEY `ix_inquiries_user` (`user_id`,`created_at`),
  KEY `ix_inquiries_category` (`origin_category`,`created_at`),
  CONSTRAINT `fk_inquiries_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CS 문의 — 답변 등록이 곧 종결이며 재문의 경로가 없다';

CREATE TABLE `daily_service_stats` (
  `stat_date` date NOT NULL COMMENT 'KST 달력 날짜. 이벤트 지표는 이 날 [00:00, 24:00) KST 에 일어난 사건, 인증 지표는 귀속일이 이 날인 판정',
  `signups` int NOT NULL DEFAULT '0' COMMENT '가입 — 이 날 생성된 MEMBER 계정 수(이후 탈퇴해도 뺄셈하지 않는다)',
  `challenges_created` int NOT NULL DEFAULT '0' COMMENT '유저가 개설한 챌린지 수 — 개설자는 방장으로 곧바로 참여한다(가입 사건이 없다)',
  `challenge_joins` int NOT NULL DEFAULT '0' COMMENT '챌린지 가입 사건 수(challenge_join_events). 같은 방 재입장 포함, 방장 개설 제외',
  `rejoins` int NOT NULL DEFAULT '0' COMMENT '그중 같은 방 재입장 — 이 방에 이전 참여 기록이 있던 가입',
  `participants` int NOT NULL DEFAULT '0' COMMENT '이 날 참여를 시작한(가입 또는 개설) 유저 수 — 중복 제거',
  `returning_participants` int NOT NULL DEFAULT '0' COMMENT '그중 이 날 이전에 다른 챌린지 참여 이력이 있던 유저 수(재참여)',
  `verification_targets` int NOT NULL DEFAULT '0' COMMENT '귀속일이 이 날이고 인증이 필요했던 판정 수(대상 아님·불필요 제외)',
  `verification_attempts` int NOT NULL DEFAULT '0' COMMENT '실제 인증 시도 — 유효한 증거가 접수된 판정 수. 판정(멤버×귀속일) 단위라 재전송이 곱해지지 않는다',
  `judged_success` int NOT NULL DEFAULT '0' COMMENT '판정 성공(이의 인용 포함)',
  `judged_success_appeal` int NOT NULL DEFAULT '0' COMMENT '그중 이의 인용으로 정정된 성공',
  `judged_fail` int NOT NULL DEFAULT '0' COMMENT '판정 실패',
  `judged_fail_no_evidence` int NOT NULL DEFAULT '0' COMMENT '그중 판정 불가(권한 없음·신호 없음) 실패',
  `judgement_pending` int NOT NULL DEFAULT '0' COMMENT '계산 시점에 아직 확정되지 않은 판정',
  `judgement_final` tinyint(1) NOT NULL DEFAULT '0' COMMENT '계산 시각이 귀속일 확정 시각(D+2 00:00 KST) 이후였는지 — 0 이면 판정 칸은 잠정값',
  `appeals` int NOT NULL DEFAULT '0' COMMENT '이의 접수 수 — 이 날 접수(=인용)된 이의',
  `computed_at` datetime(3) NOT NULL COMMENT '마지막 계산 시각(UTC 벽시계)',
  PRIMARY KEY (`stat_date`),
  CONSTRAINT `chk_daily_service_stats_judgement_final` CHECK ((`judgement_final` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='일별 서비스 지표 — 건수만, 개인정보 없음';
