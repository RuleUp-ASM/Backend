-- =====================================================================
-- V28: 감시자 재설계 — 패널티 감시자 공통 5-3 · 백엔드 4-1
--
--  기존 구현은 폐지된 설계 위에 서 있었다. SMS·이메일 채널, 비유저 감시자, OTP 인증,
--  웹 동의, 관계 해제, 무료 3명 한도 — 전부 정책에서 사라진 개념이다.
--
--  가장 무거운 건 연락처였다. Watcher.contactEnc·contactMasked 와 WatcherOtp.phoneEnc 가
--  제3자의 전화번호를 들고 있었는데, 새 스펙의 절대 원칙은 **연락처를 수집하지 않는다**이며
--  그 방어를 마스킹이나 암호화가 아니라 **스키마에 자리를 두지 않는 방식**으로 한다.
--  자리가 없으면 실수로도 수집할 수 없기 때문이다. 그래서 컬럼을 비우는 게 아니라 테이블째 지운다.
--
--  ⚠️ 기존 데이터는 이관하지 않는다. 구 Watcher 행은 대부분 비유저·SMS 전제라 새 모델의
--     watcher_user_id(룰업 유저)를 채울 수 없고, 채워지는 일부도 동의 근거가 다르다
--     (웹 동의·OTP 로 받은 동의를 인앱 수락 동의로 둔갑시키면 그게 곧 무동의 발송이 된다).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 감시 관계 본체
-- ---------------------------------------------------------------------
CREATE TABLE `watcher_relations` (
    `id`              BINARY(16) NOT NULL COMMENT 'UUIDv7',
    `challenge_id`    BINARY(16) NOT NULL,
    `target_user_id`  BINARY(16) NOT NULL COMMENT '감시를 받는 참여자 — 초대한 본인',
    `watcher_user_id` BINARY(16) NOT NULL COMMENT '감시자 — 룰업 앱 유저만 가능',
    -- PENDING / ACTIVE 2종뿐. 해제 개념이 없어 REMOVED 상태를 두지 않는다.
    `status`          VARCHAR(10) NOT NULL,
    -- 수신 토글. 관계를 끊지 않고 통지만 닫는다.
    -- 스펙 5-3 표에는 없는 컬럼이지만 API #6 의 현재 상태를 담을 곳이 필요하다. 이력에서
    -- 파생하면 발송 대상 조회가 매번 로그를 훑어야 해서 아래 dispatch 인덱스의 의미가 사라진다.
    `push_enabled`    TINYINT(1) NOT NULL DEFAULT 1,
    `invited_at`      DATETIME(3) NOT NULL,
    -- 동의 시각 — 입증 책임의 근거. NULL 이면 PENDING 이며 발송 대상이 아니다.
    `accepted_at`     DATETIME(3) NULL,
    -- 루틴 종료 시 배치가 채운다 — 유저가 끊는 경로는 없다.
    `removed_at`      DATETIME(3) NULL,
    PRIMARY KEY (`id`),
    -- 같은 사람을 같은 방에 두 번 등록하는 것을 막는다. 수락 처리가 멱등해지는 근거이기도 하다.
    UNIQUE KEY `uq_watcher_relation` (`challenge_id`, `target_user_id`, `watcher_user_id`),
    -- 발송 대상 조회의 주 인덱스. status·push_enabled 필터까지 인덱스 안에서 끝나야
    -- "PENDING 발송 0건" 가드레일이 성능 문제 없이 지켜진다.
    KEY `ix_watcher_relation_dispatch` (`challenge_id`, `target_user_id`, `status`, `push_enabled`),
    -- 마이페이지 패널티 수신 관리 — 내가 감시자로 등록된 관계.
    KEY `ix_watcher_relation_watcher` (`watcher_user_id`, `status`, `removed_at`),
    -- 루틴 종료 자동 제거 배치. 종료된 루틴의 잔존 ACTIVE 0건 감사 쿼리도 이걸 쓴다.
    KEY `ix_watcher_relation_cleanup` (`challenge_id`, `removed_at`),
    CONSTRAINT `fk_watcher_relation_challenge`
        FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_watcher_relation_target` FOREIGN KEY (`target_user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `fk_watcher_relation_watcher` FOREIGN KEY (`watcher_user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `chk_watcher_relation_push` CHECK (`push_enabled` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='감시 관계 — (챌린지, 피감시자, 감시자) 3중 키. 연락처 컬럼을 두지 않는다';

-- ---------------------------------------------------------------------
-- 2) 초대 토큰
--
--  카카오톡으로 외부에 나가므로 URL 에 개인정보를 담지 않고 원본 토큰도 저장하지 않는다.
-- ---------------------------------------------------------------------
CREATE TABLE `watcher_invitations` (
    `id`                 BINARY(16) NOT NULL COMMENT 'UUIDv7',
    `token_hash`         CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                         COMMENT 'SHA-256 — 원본 미저장',
    `challenge_id`       BINARY(16) NOT NULL,
    `inviter_user_id`    BINARY(16) NOT NULL,
    `expires_at`         DATETIME(3) NOT NULL COMMENT '발급 + 7일',
    `accepted_at`        DATETIME(3) NULL,
    -- 생성자에게 만료 알림을 보낸 시각 — 중복 발송 방지.
    -- 감시자 후보에게는 어떤 알림도 보내지 않는다(아직 동의하지 않은 외부인).
    `expiry_notified_at` DATETIME(3) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_watcher_invitation_token` (`token_hash`),
    -- 만료 처리 배치 — 미수락이면서 만료됐고 아직 알리지 않은 건.
    -- 미수락이 극소수라 accepted_at 을 선두 컬럼으로 둔다.
    KEY `ix_watcher_invitation_expiry` (`accepted_at`, `expires_at`, `expiry_notified_at`),
    CONSTRAINT `fk_watcher_invitation_challenge`
        FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_watcher_invitation_inviter`
        FOREIGN KEY (`inviter_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='초대 토큰 — 해시만 보관, 7일 만료';

-- ---------------------------------------------------------------------
-- 3) 실패 통지 발송 기록
--
--  verification_id 가 감사의 조인 키다. sent_at 과 해당 인증 건의 확정 시각을 대조해
--  "조기 발송 0건"을 감사하며, 1건이라도 나오면 통지 플래그를 즉시 내린다.
-- ---------------------------------------------------------------------
CREATE TABLE `watcher_notices` (
    `id`              BINARY(16) NOT NULL COMMENT 'UUIDv7',
    `relation_id`     BINARY(16) NOT NULL,
    `verification_id` BINARY(16) NOT NULL COMMENT '근거가 된 인증 건 — 감사의 조인 키',
    `sent_at`         DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    -- 통지 멱등성 — 인증 모듈의 확정 이벤트가 재전송돼도 같은 건으로 두 번 발송되지 않는다.
    UNIQUE KEY `uq_watcher_notice_dedup` (`relation_id`, `verification_id`),
    -- 조기 발송 감사의 핵심 — 확정 시각과 조인해 대조한다.
    KEY `ix_watcher_notice_verification` (`verification_id`),
    CONSTRAINT `fk_watcher_notice_relation`
        FOREIGN KEY (`relation_id`) REFERENCES `watcher_relations` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='실패 통지 발송 기록 — 조기 발송 감사의 원천';

-- ---------------------------------------------------------------------
-- 4) 응원·놀림
--
--  1회 제한을 서버 카운터가 아니라 복합 PK 로 보장한다 — 경합에서도 초과되지 않게.
--  조회가 항상 두 값을 모두 가지고 들어오므로 추가 인덱스가 필요 없다.
-- ---------------------------------------------------------------------
CREATE TABLE `watcher_reactions` (
    `notice_id`       BINARY(16) NOT NULL,
    `watcher_user_id` BINARY(16) NOT NULL COMMENT '반응한 감시자 — 닉네임을 공개한다',
    `reaction`        VARCHAR(10) NOT NULL COMMENT 'CHEER / TEASE — 둘 다 보낼 수 없음',
    `created_at`      DATETIME(3) NOT NULL,
    PRIMARY KEY (`notice_id`, `watcher_user_id`),
    CONSTRAINT `fk_watcher_reaction_notice`
        FOREIGN KEY (`notice_id`) REFERENCES `watcher_notices` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_watcher_reaction_user`
        FOREIGN KEY (`watcher_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='응원·놀림 — 실패 건당 1회를 DB 제약으로 보장';

-- ---------------------------------------------------------------------
-- 5) 동의·철회 이력
--
--  제3자 동의는 입증 책임이 사업자에게 있어 "언제 동의했고 언제 닫았는지"를 재구성할 수
--  있어야 한다. 페이지2에서 채널·범위가 세분화되면 여기에 필드를 추가한다.
-- ---------------------------------------------------------------------
CREATE TABLE `watcher_consent_logs` (
    `id`          BINARY(16) NOT NULL COMMENT 'UUIDv7',
    `relation_id` BINARY(16) NOT NULL,
    `event`       VARCHAR(20) NOT NULL COMMENT 'ACCEPTED / TOGGLE_OFF / BLOCKED',
    `occurred_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    -- 분쟁 시 입증 자료 — 관계별 시간순 조회.
    KEY `ix_watcher_consent_relation` (`relation_id`, `occurred_at`),
    CONSTRAINT `fk_watcher_consent_relation`
        FOREIGN KEY (`relation_id`) REFERENCES `watcher_relations` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='동의·철회 이력(append-only) — 입증 책임의 근거';

-- ---------------------------------------------------------------------
-- 6) 구 스키마 제거
--
--  FK 의존 순서대로 지운다. 연락처를 들고 있던 두 테이블(Watcher·WatcherOtp)이
--  여기서 사라지는 것이 이 마이그레이션의 핵심이다.
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `WatcherNotification`;
DROP TABLE IF EXISTS `WatcherOtp`;
DROP TABLE IF EXISTS `WatcherBlock`;
DROP TABLE IF EXISTS `Watcher`;
DROP TABLE IF EXISTS `WatcherInvitation`;
