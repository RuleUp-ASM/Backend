-- ======================================================================
-- verification — 인증·판정과 그 원본 신호
--
-- 이 파일은 한 도메인의 표를 모아 둔다. 파일 안의 순서는 외래키 방향이고,
-- 파일 사이의 순서도 같다 — 앞 파일만 뒤 파일을 가리킨다.
-- ======================================================================

CREATE TABLE `VerificationDaily` (
  `id` binary(16) NOT NULL,
  `challengeMemberId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `targetDate` date NOT NULL,
  `status` enum('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '저장 상태. 진행중·실패 예정·검사중은 저장하지 않고 조회 시 계산한다',
  `method` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `failureReason` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `gapReason` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '판정 불가 사유 — PERMISSION_MISSING / NO_SIGNAL. 실패 사유와 층이 다르다',
  `windowClosesAt` datetime(6) DEFAULT NULL,
  `finalizeAfter` datetime(6) DEFAULT NULL COMMENT '최종 확정 시각 — 귀속일+2일 00:00 KST. 판정 유형과 무관하게 같다',
  `verifiedAt` datetime(6) DEFAULT NULL,
  `acknowledgedAt` datetime(6) DEFAULT NULL COMMENT '판정 결과 모달 확인(POST /verifications/{id}/ack) 시각. NULL이면 today 응답에 unacknowledgedResult로 내려간다',
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `verifiedVia` enum('AUTO','MANUAL','APPEAL') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '확정 경로. AUTO=신호 판정 / MANUAL=수동 체크 / APPEAL=이의 인용 정정',
  `appealClosesAt` datetime(6) DEFAULT NULL COMMENT '이의 신청 기한 — 확정 시각과 같은 귀속일+2일 00:00 KST. 확정 전에 받는다(상대 24시간 아님)',
  `shareableAt` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0' COMMENT '낙관적 락 — sync 와 확정 배치가 같은 행을 갱신할 때 덮어쓰기를 막는다',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqVerificationDailyMemberDate` (`challengeMemberId`,`targetDate`),
  KEY `idxVerificationDailyStatusFinalize` (`status`,`finalizeAfter`),
  KEY `ixVerificationDailyThread` (`challengeId`,`status`,`shareableAt`,`verifiedAt`),
  KEY `ixVerificationDailyUserDate` (`userId`,`targetDate`,`status`),
  KEY `idx_verification_user_date_challenge` (`userId`,`targetDate`,`challengeId`),
  KEY `idx_verification_user_status` (`userId`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `VerificationMethodResult` (
  `id` binary(16) NOT NULL,
  `verificationDailyId` binary(16) NOT NULL,
  `method` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `polarity` enum('ACHIEVEMENT','CONSTRAINT') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `supported` tinyint(1) NOT NULL DEFAULT '1',
  `status` enum('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `evidence` json DEFAULT NULL,
  `lastEvaluatedAt` datetime(6) DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqMethodResultDailyMethod` (`verificationDailyId`,`method`),
  CONSTRAINT `fkMethodResultDaily` FOREIGN KEY (`verificationDailyId`) REFERENCES `VerificationDaily` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `verification_appeals` (
  `id` binary(16) NOT NULL,
  `verificationDailyId` binary(16) NOT NULL COMMENT '이의 대상 인증(= API verificationId). 실패 결과 기준 멱등 앵커',
  `challengeId` binary(16) NOT NULL,
  `challengeMemberId` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL COMMENT '신청자(= 인증 당사자)',
  `targetDate` date NOT NULL COMMENT '이의 대상 귀속일(KST)',
  `reason` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '이의 사유. 10자 이상만 접수된다. 내용의 진위는 판단하지 않는다',
  `imageUrl` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '증빙 사진(선택). 저장만 하고 판단에 쓰지 않는다 — 이상탐지의 동일 이미지 반복 입력',
  `acceptedAt` datetime(6) NOT NULL COMMENT '인용 시각. 접수 = 인용이라 접수 시각과 같다',
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_verification_appeals_verification` (`verificationDailyId`),
  KEY `idx_verification_appeals_user_accepted` (`userId`,`acceptedAt`),
  CONSTRAINT `fk_verification_appeals_user` FOREIGN KEY (`userId`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_verification_appeals_verification` FOREIGN KEY (`verificationDailyId`) REFERENCES `VerificationDaily` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='인증 이의. 접수된 행은 전부 인용된 이의다 — 기각 상태가 존재하지 않는다';

CREATE TABLE `verification_failure_details` (
  `verificationDailyId` binary(16) NOT NULL COMMENT '실패한 인증 결과 — 1:0..1',
  `reasonCode` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '실패 사유 코드 — 자동화된 결정의 설명 근거',
  `evidenceSummary` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '사용자에게 설명 가능한 판정 근거 요약',
  `expectedValue` json DEFAULT NULL COMMENT '적용된 성공 조건·기준값 스냅샷',
  `actualValue` json DEFAULT NULL COMMENT '최종 판정에 사용된 실제 값',
  `createdAt` datetime(3) NOT NULL,
  PRIMARY KEY (`verificationDailyId`),
  KEY `idx_verification_failure_reason` (`reasonCode`),
  CONSTRAINT `fk_verification_failure_daily` FOREIGN KEY (`verificationDailyId`) REFERENCES `VerificationDaily` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='최종 FAILED 인증의 실패 상세 — 실패 예정은 계산 상태라 행을 만들지 않는다';

CREATE TABLE `verification_setting_snapshots` (
  `id` binary(16) NOT NULL,
  `challengeMemberId` binary(16) NOT NULL,
  `kind` enum('ANCHORS','SCREEN_APPS') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `effectiveFrom` date NOT NULL COMMENT '이 설정이 판정에 쓰이기 시작하는 KST 날짜',
  `payload` json NOT NULL COMMENT '설정 값 원본(GeoAnchor[] 또는 ScreenApp[])',
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_setting_snapshots_lookup` (`challengeMemberId`,`kind`,`effectiveFrom`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='멤버 인증 설정의 시점 스냅샷. 과거 날짜는 그 날 적용되던 설정으로 평가한다';

CREATE TABLE `verification_sync_sessions` (
  `id` binary(16) NOT NULL COMMENT '세션 ID — 인트로 응답의 sessionId',
  `userId` binary(16) NOT NULL,
  `deviceId` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '인트로 당시 기기. 활성 기기 판정은 users.device_id 가 원본',
  `appVersion` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sdkInt` int DEFAULT NULL COMMENT '수집 주기 산정 근거 — 기종별 차이를 나중에 되짚는다',
  `issuedAt` datetime(3) NOT NULL,
  `lastSeenAt` datetime(3) DEFAULT NULL COMMENT '이 세션으로 마지막 sync 가 들어온 시각',
  PRIMARY KEY (`id`),
  KEY `idx_sync_session_user` (`userId`,`issuedAt` DESC),
  CONSTRAINT `fk_sync_sessions_user` FOREIGN KEY (`userId`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='인트로가 발급한 sync 세션 — 어느 기기가 어떤 정책으로 수집을 시작했는지';

CREATE TABLE `verification_permission_waits` (
  `challenge_id` binary(16) NOT NULL,
  `user_id` binary(16) NOT NULL,
  `signal_type` varchar(30) NOT NULL,
  `source_event_id` binary(16) NOT NULL,
  `first_observed_at` datetime(6) NOT NULL,
  `waiting_from_on` date NOT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  `dispatched_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`challenge_id`,`user_id`,`signal_type`),
  CONSTRAINT `fk_permission_wait_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `verification_batch_completions` (
  `batch_on` date NOT NULL,
  `materialized_at` datetime(6) DEFAULT NULL,
  `finalized_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`batch_on`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `verification_signals` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `dedupKey` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '멱등 키(SHA-256 hex). recordId 가 있으면 그것으로, 없으면 신호 내용 전체로 만든다',
  `signalType` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `observedAt` datetime(6) DEFAULT NULL COMMENT '신호 관측 시각(클라 선언). 파싱 불가면 NULL',
  `receivedAt` datetime(6) NOT NULL COMMENT '서버 수신 시각',
  `payload` json NOT NULL COMMENT '신호 원본. 압축·요약하지 않는다',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_verification_signals_dedup` (`userId`,`dedupKey`),
  KEY `idx_verification_signals_user_observed` (`userId`,`observedAt`),
  KEY `idx_verification_signals_received` (`receivedAt`),
  CONSTRAINT `fk_verification_signals_user` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='수신한 인증 신호 원본. uq(userId, dedupKey)가 재전송 중복을 끊는다';

CREATE TABLE `verification_device_usage_signals` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `observedDate` date NOT NULL COMMENT 'KST 귀속일 — 파티션 키',
  `userId` binary(16) NOT NULL,
  `deviceId` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '출처 기기 — 계약에 생기면 채운다',
  `signalType` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SCREEN_TIME / WAKE',
  `excludeReason` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '판정 배제 사유. NULL 이면 판정 입력',
  `occurredAt` datetime(3) DEFAULT NULL COMMENT '발생 시각 — 날짜 귀속의 기준',
  `receivedAt` datetime(3) NOT NULL,
  `payload` json NOT NULL COMMENT '원본 — 앱 사용 시작·종료, 화면 켜짐·잠금해제',
  `dedupKey` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (`observedDate`,`id`),
  UNIQUE KEY `uq_device_usage_signal_dedup` (`observedDate`,`userId`,`dedupKey`),
  KEY `idx_device_usage_signal_judge` (`userId`,`occurredAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='앱 사용·화면 이벤트 원본. 전송량의 대부분을 차지하는 테이블이다'
/*!50100 PARTITION BY RANGE (to_days(`observedDate`))
(PARTITION pFuture VALUES LESS THAN MAXVALUE ENGINE = InnoDB) */;

CREATE TABLE `verification_health_connect_signals` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `observedDate` date NOT NULL COMMENT 'KST 귀속일 — 파티션 키',
  `userId` binary(16) NOT NULL,
  `deviceId` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '출처 기기 — 계약에 생기면 채운다',
  `signalType` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'HEALTH / SLEEP',
  `excludeReason` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '판정 배제 사유. NULL 이면 판정 입력',
  `occurredAt` datetime(3) DEFAULT NULL COMMENT '발생 시각 — 수면은 밤이 시작된 날짜로 귀속한다',
  `receivedAt` datetime(3) NOT NULL,
  `payload` json NOT NULL COMMENT '원본 — metric·recordingMethod·originPackage 등 위생 검증 정보 포함',
  `dedupKey` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (`observedDate`,`id`),
  UNIQUE KEY `uq_health_connect_signal_dedup` (`observedDate`,`userId`,`dedupKey`),
  KEY `idx_health_connect_signal_judge` (`userId`,`occurredAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Health Connect 걸음·거리·수면 원본'
/*!50100 PARTITION BY RANGE (to_days(`observedDate`))
(PARTITION pFuture VALUES LESS THAN MAXVALUE ENGINE = InnoDB) */;

CREATE TABLE `verification_location_signals` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `observedDate` date NOT NULL COMMENT 'KST 귀속일 — 파티션 키',
  `userId` binary(16) NOT NULL,
  `verificationId` binary(16) DEFAULT NULL COMMENT '이 좌표를 소비한 판정 — 파기 타이머의 기준 확정 시각을 여기서 읽는다',
  `deviceId` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '출처 기기 — 계약에 생기면 채운다',
  `signalType` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'LOCATION / GEOFENCE',
  `excludeReason` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '판정 배제 사유 — MOCK / VPN / UNTRUSTED_SOURCE / ACCURACY_LOW / MANUAL_ENTRY. NULL 이면 판정 입력',
  `occurredAt` datetime(3) DEFAULT NULL COMMENT '발생 시각 — 날짜 귀속의 기준. 파싱 불가면 NULL',
  `receivedAt` datetime(3) NOT NULL COMMENT '서버 수신 시각',
  `payload` json NOT NULL COMMENT '원본 — 좌표·정확도·mock 여부·출처. 압축·요약하지 않는다',
  `purgeAfter` datetime(3) DEFAULT NULL COMMENT '확정 시각 + 보관 기간. 고정 일괄 시각이 아니라 확정 전 건이 지워지지 않는다',
  `purgedAt` datetime(3) DEFAULT NULL COMMENT '실제 파기 시각 — 이후에는 판정 결과와 요약만 남는다',
  `dedupKey` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (`observedDate`,`id`),
  UNIQUE KEY `uq_location_signal_dedup` (`observedDate`,`userId`,`dedupKey`),
  KEY `idx_location_signal_judge` (`userId`,`occurredAt`),
  KEY `idx_location_purge` (`purgedAt`,`purgeAfter`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='GPS·지오펜스 원본. 위치정보라 별도 도메인 — 파티션 DROP 이 곧 파기다'
/*!50100 PARTITION BY RANGE (to_days(`observedDate`))
(PARTITION pFuture VALUES LESS THAN MAXVALUE ENGINE = InnoDB) */;

CREATE TABLE `signal_exclusions` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `userId` binary(16) NOT NULL,
  `verificationDailyId` binary(16) DEFAULT NULL COMMENT '배제가 영향을 준 판정. 게이트 단계면 NULL',
  `signalType` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'LOCATION / GEOFENCE / HEALTH / SCREEN_TIME / WAKE / SLEEP',
  `reason` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MOCK / VPN / UNTRUSTED_SOURCE / ACCURACY_LOW / MANUAL_ENTRY',
  `signalCount` int NOT NULL DEFAULT '1' COMMENT '이번에 배제한 신호 수',
  `excludedAt` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_exclusion_anomaly` (`userId`,`excludedAt`,`reason`),
  KEY `fk_signal_exclusions_daily` (`verificationDailyId`),
  CONSTRAINT `fk_signal_exclusions_daily` FOREIGN KEY (`verificationDailyId`) REFERENCES `VerificationDaily` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_signal_exclusions_user` FOREIGN KEY (`userId`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='신호 위생 배제 로그 — 제재가 아니라 이상패턴 탐지의 입력';

CREATE TABLE `cheat_detections` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `userId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL COMMENT '집계 단위 — 차단은 이 방에만 걸린다',
  `verificationDailyId` binary(16) NOT NULL COMMENT '무효화된 인증',
  `pattern` json NOT NULL COMMENT '탐지 근거 — 반복 위조·불가능한 이동량·자동화 도구 패턴',
  `detectedAt` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_cheat_verification` (`verificationDailyId`),
  KEY `idx_cheat_user` (`userId`,`detectedAt` DESC),
  KEY `idx_cheat_challenge` (`challengeId`,`userId`),
  CONSTRAINT `fk_cheat_detections_user` FOREIGN KEY (`userId`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='이상패턴 탐지로 확정된 부정행위 — 1건이 곧 강퇴·영구 차단·−50';

CREATE TABLE `anomaly_signals` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `signal_type` varchar(30) NOT NULL COMMENT 'REPORT_ABUSE / APPEAL_ABUSE / MODERATION_EVASION',
  `target_user_id` binary(16) NOT NULL,
  `score` int NOT NULL COMMENT '탐지 강도 — 임계값은 서버 설정이며 넉넉히 잡고 조정',
  `detected_at` datetime(3) NOT NULL,
  `reviewed_at` datetime(3) DEFAULT NULL COMMENT 'null 이면 미검토',
  `reviewer_id` binary(16) DEFAULT NULL,
  `review_note` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_anomaly_queue` (`reviewed_at`,`signal_type`,`score` DESC,`detected_at`),
  KEY `ix_anomaly_target` (`target_user_id`,`detected_at` DESC),
  CONSTRAINT `fk_anomaly_target` FOREIGN KEY (`target_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='이상탐지 — 탐지만으로는 제재하지 않고 검토 대상으로만 분류';

CREATE TABLE `anomaly_device_usage_events` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `observedDate` date NOT NULL COMMENT 'KST 발생일 — 파티션 키',
  `userId` binary(16) NOT NULL,
  `verificationId` binary(16) DEFAULT NULL,
  `eventType` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'FEATURE / HYGIENE',
  `signalType` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `anomalyType` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `signalCount` int NOT NULL DEFAULT '1',
  `features` json DEFAULT NULL,
  `observedAt` datetime(3) NOT NULL,
  PRIMARY KEY (`observedDate`,`id`),
  KEY `idx_anomaly_device_usage_user` (`userId`,`observedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='앱 사용·화면 이벤트 이상탐지 입력'
/*!50100 PARTITION BY RANGE (to_days(`observedDate`))
(PARTITION pFuture VALUES LESS THAN MAXVALUE ENGINE = InnoDB) */;

CREATE TABLE `anomaly_health_connect_events` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `observedDate` date NOT NULL COMMENT 'KST 발생일 — 파티션 키',
  `userId` binary(16) NOT NULL,
  `verificationId` binary(16) DEFAULT NULL,
  `eventType` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'FEATURE / HYGIENE',
  `signalType` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `anomalyType` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `signalCount` int NOT NULL DEFAULT '1',
  `features` json DEFAULT NULL,
  `observedAt` datetime(3) NOT NULL,
  PRIMARY KEY (`observedDate`,`id`),
  KEY `idx_anomaly_health_connect_user` (`userId`,`observedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='걸음·거리·수면 이상탐지 입력'
/*!50100 PARTITION BY RANGE (to_days(`observedDate`))
(PARTITION pFuture VALUES LESS THAN MAXVALUE ENGINE = InnoDB) */;

CREATE TABLE `anomaly_location_events` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `observedDate` date NOT NULL COMMENT 'KST 발생일 — 파티션 키',
  `userId` binary(16) NOT NULL,
  `verificationId` binary(16) DEFAULT NULL COMMENT '근거가 된 판정. 게이트 단계 이상은 NULL',
  `eventType` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'FEATURE(성공 인증 추출) / HYGIENE(신호 위생 이상)',
  `signalType` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `anomalyType` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'MOCK / VPN / UNTRUSTED_SOURCE / ACCURACY_LOW / MANUAL_ENTRY',
  `signalCount` int NOT NULL DEFAULT '1',
  `features` json DEFAULT NULL COMMENT '탐지 feature — 좌표 원본이 아니라 파생값만',
  `observedAt` datetime(3) NOT NULL,
  PRIMARY KEY (`observedDate`,`id`),
  KEY `idx_anomaly_location_user` (`userId`,`observedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='위치 이상탐지 입력 — 좌표 원본이 아니라 파생 feature 만 30일'
/*!50100 PARTITION BY RANGE (to_days(`observedDate`))
(PARTITION pFuture VALUES LESS THAN MAXVALUE ENGINE = InnoDB) */;

CREATE TABLE `device_sync_policies` (
  `id` binary(16) NOT NULL,
  `priority` int NOT NULL,
  `match_condition` json NOT NULL,
  `flush_interval_sec` int NOT NULL,
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `priority` (`priority`),
  CONSTRAINT `ck_sync_interval` CHECK ((`flush_interval_sec` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `outage_reliefs` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `period_start` datetime(3) NOT NULL,
  `period_end` datetime(3) NOT NULL,
  `scope` varchar(30) NOT NULL COMMENT 'ALL / VERIFY_TYPE',
  `operator_id` binary(16) NOT NULL,
  `affected_count` int DEFAULT NULL COMMENT '적용 전에 미리 보여주고 확인받은 영향 판정 건수',
  `applied_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_relief_period` (`period_start`,`period_end`),
  KEY `fk_relief_operator` (`operator_id`),
  CONSTRAINT `fk_relief_operator` FOREIGN KEY (`operator_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='장애 구제 — 성공 처리가 아니라 분모에서 제외';

-- 기준 데이터 — 코드가 이 행들의 존재를 전제한다.

INSERT INTO `device_sync_policies` (`id`, `priority`, `match_condition`, `flush_interval_sec`, `is_active`) VALUES (0x01950000000070008000000000000001, 100, '{\"lowRam\": true}', 3600, 1);

INSERT INTO `device_sync_policies` (`id`, `priority`, `match_condition`, `flush_interval_sec`, `is_active`) VALUES (0x01950000000070008000000000000002, 50, '{\"maxSdk\": 25, \"platform\": \"ANDROID\"}', 3600, 1);

INSERT INTO `device_sync_policies` (`id`, `priority`, `match_condition`, `flush_interval_sec`, `is_active`) VALUES (0x01950000000070008000000000000003, 0, '{}', 1800, 1);
