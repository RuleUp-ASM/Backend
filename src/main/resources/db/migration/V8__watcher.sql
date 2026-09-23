-- ======================================================================
-- watcher — 감시자 관계와 응원
--
-- 이 파일은 한 도메인의 표를 모아 둔다. 파일 안의 순서는 외래키 방향이고,
-- 파일 사이의 순서도 같다 — 앞 파일만 뒤 파일을 가리킨다.
-- ======================================================================

CREATE TABLE `watcher_relations` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `challenge_id` binary(16) NOT NULL,
  `target_user_id` binary(16) NOT NULL COMMENT '감시를 받는 참여자 — 초대한 본인',
  `watcher_user_id` binary(16) NOT NULL COMMENT '감시자 — 룰업 앱 유저만 가능',
  `status` varchar(10) NOT NULL,
  `invited_at` datetime(3) NOT NULL,
  `accepted_at` datetime(3) DEFAULT NULL,
  `removed_at` datetime(3) DEFAULT NULL,
  `consent_version` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_watcher_relation` (`challenge_id`,`target_user_id`,`watcher_user_id`),
  KEY `ix_watcher_relation_watcher` (`watcher_user_id`,`status`,`removed_at`),
  KEY `ix_watcher_relation_cleanup` (`challenge_id`,`removed_at`),
  KEY `fk_watcher_relation_target` (`target_user_id`),
  KEY `ix_watcher_relation_dispatch` (`challenge_id`,`target_user_id`,`status`),
  CONSTRAINT `fk_watcher_relation_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_watcher_relation_target` FOREIGN KEY (`target_user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_watcher_relation_watcher` FOREIGN KEY (`watcher_user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `chk_watcher_consent` CHECK (((`status` in (_utf8mb4'PENDING',_utf8mb4'ACTIVE')) and ((`status` <> _utf8mb4'ACTIVE') or ((`accepted_at` is not null) and (`consent_version` is not null) and (`consent_version` <> _utf8mb4'')))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='감시 관계 — (챌린지, 피감시자, 감시자) 3중 키. 연락처 컬럼을 두지 않는다';

CREATE TABLE `watcher_invitations` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `challenge_id` binary(16) NOT NULL,
  `inviter_user_id` binary(16) NOT NULL,
  `expires_at` datetime(3) NOT NULL COMMENT '발급 + 7일',
  `accepted_at` datetime(3) DEFAULT NULL,
  `expiry_notified_at` datetime(3) DEFAULT NULL,
  `token_hash` binary(32) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_watcher_invitation_token` (`token_hash`),
  KEY `ix_watcher_invitation_expiry` (`accepted_at`,`expires_at`,`expiry_notified_at`),
  KEY `fk_watcher_invitation_challenge` (`challenge_id`),
  KEY `fk_watcher_invitation_inviter` (`inviter_user_id`),
  CONSTRAINT `fk_watcher_invitation_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_watcher_invitation_inviter` FOREIGN KEY (`inviter_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='초대 토큰 — 해시만 보관, 7일 만료';

CREATE TABLE `watcher_notices` (
  `id` binary(16) NOT NULL COMMENT 'UUIDv7',
  `relation_id` binary(16) NOT NULL,
  `verification_id` binary(16) NOT NULL COMMENT '근거가 된 인증 건 — 감사의 조인 키',
  `sent_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_watcher_notice_dedup` (`relation_id`,`verification_id`),
  KEY `ix_watcher_notice_verification` (`verification_id`),
  CONSTRAINT `fk_watcher_notice_relation` FOREIGN KEY (`relation_id`) REFERENCES `watcher_relations` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='실패 통지 발송 기록 — 조기 발송 감사의 원천';

CREATE TABLE `watcher_reactions` (
  `notice_id` binary(16) NOT NULL,
  `watcher_user_id` binary(16) NOT NULL COMMENT '반응한 감시자 — 닉네임을 공개한다',
  `reaction` varchar(10) NOT NULL COMMENT 'CHEER / TEASE — 둘 다 보낼 수 없음',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`notice_id`,`watcher_user_id`),
  KEY `fk_watcher_reaction_user` (`watcher_user_id`),
  CONSTRAINT `fk_watcher_reaction_notice` FOREIGN KEY (`notice_id`) REFERENCES `watcher_notices` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_watcher_reaction_user` FOREIGN KEY (`watcher_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='응원·놀림 — 실패 건당 1회를 DB 제약으로 보장';
