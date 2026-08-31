-- =====================================================================
-- V29: 운영자 백오피스 — 백오피스 공통 5-3 · 백엔드 4-1
--
--  이 모듈이 존재해야 하는 이유는 하나다. 페이지1의 모든 계정 제재는 **사람의 검토를 반드시
--  거치도록** 설계돼 있다 — 신고는 임계값 없이 전건 적재되고 적재 자체는 어떤 제재도
--  발동시키지 않으며, 이상탐지도 탐지만으로는 제재하지 않는다. 그 유일한 경로의 도구다.
--
--  sanctions·ban_list 는 V26 에서 이미 만들었다. 여기서는 그 집행을 감사할 수 있게 하는
--  나머지를 세운다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 계정 롤
--
--  페이지1은 권한을 나누지 않는다 — 운영자냐 아니냐 둘뿐이다. 다만 감사 로그가 조작자를
--  남기므로 페이지2에서 값만 늘리면 그대로 세분화된다.
-- ---------------------------------------------------------------------
ALTER TABLE `users`
    ADD COLUMN `role` ENUM('MEMBER','OPERATOR') NOT NULL DEFAULT 'MEMBER'
        COMMENT '백오피스 접근 롤 — 개인정보 열람 권한이 붙으므로 부여 자체가 운영 결정이다'
        AFTER `status`;

-- ---------------------------------------------------------------------
-- 2) 조작 이력 — append only
--
--  삭제·수정 경로를 두지 않는다. 재검토 대응과 개인정보 열람 감사의 유일한 근거이고,
--  지울 수 있으면 근거가 아니기 때문이다. 샘플링도 하지 않는다.
--
--  ⚠️ 조회까지 전건 기록하므로 빠르게 커진다. 보관 기간은 법적·개인정보 처리정책의
--     12개월 이상 요구를 따르며, 월별 파티셔닝은 규모를 보고 검토한다.
-- ---------------------------------------------------------------------
CREATE TABLE `admin_audit_logs` (
    `id`             BINARY(16) NOT NULL COMMENT 'UUIDv7',
    -- 접근 거부도 기록하므로 운영자가 아닌 계정 ID 가 들어올 수 있다. 그래서 FK 를 걸지 않는다.
    `operator_id`    BINARY(16) NULL,
    `action`         VARCHAR(40) NOT NULL COMMENT 'SNAPSHOT_VIEW 는 개인정보 열람이라 별도 action',
    `target_type`    VARCHAR(20) NULL COMMENT 'USER / CHALLENGE / REPORT',
    `target_id`      BINARY(16) NULL,
    `result`         VARCHAR(10) NOT NULL COMMENT 'ALLOWED / DENIED',
    -- 요청 본문의 SHA-256. 본문을 통째로 남기지 않아 민감정보가 로그로 새지 않게 한다 —
    -- 제재 사유에는 신고 내용이 인용될 수 있다.
    `payload_digest` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    `occurred_at`    DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    -- 운영자별 활동 추적 · 무목적 열람 비율 점검.
    KEY `ix_audit_operator` (`operator_id`, `occurred_at` DESC),
    -- 특정 유저·챌린지에 가해진 조작 전수 조회 — 재검토 대응의 기본 쿼리.
    KEY `ix_audit_target` (`target_type`, `target_id`, `occurred_at` DESC),
    -- 일반 계정 접근 시도 탐지 — DENIED 급증이 우회 시도의 신호다.
    KEY `ix_audit_denied` (`result`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='운영 조작 이력(append only) — 조회도 기록한다';

-- ---------------------------------------------------------------------
-- 3) 이상탐지 신호
--
--  탐지만으로는 제재하지 않는다. 여기서 자동으로 제재로 승격하는 경로를 두지 않는 것이
--  "검토 없이 발동된 계정 제재 0건" 가드레일의 실체다.
-- ---------------------------------------------------------------------
CREATE TABLE `anomaly_signals` (
    `id`             BINARY(16) NOT NULL COMMENT 'UUIDv7',
    `signal_type`    VARCHAR(30) NOT NULL COMMENT 'REPORT_ABUSE / APPEAL_ABUSE / MODERATION_EVASION',
    `target_user_id` BINARY(16) NOT NULL,
    `score`          INT NOT NULL COMMENT '탐지 강도 — 임계값은 서버 설정이며 넉넉히 잡고 조정',
    `detected_at`    DATETIME(3) NOT NULL,
    `reviewed_at`    DATETIME(3) NULL COMMENT 'null 이면 미검토',
    `reviewer_id`    BINARY(16) NULL,
    PRIMARY KEY (`id`),
    -- 미검토 신호를 강도순으로 꺼내는 대시보드 주 쿼리. reviewed_at IS NULL 필터가 먼저다.
    KEY `ix_anomaly_queue` (`reviewed_at`, `signal_type`, `score` DESC, `detected_at`),
    -- 유저 통합 뷰의 이상탐지 이력 섹션.
    KEY `ix_anomaly_target` (`target_user_id`, `detected_at` DESC),
    CONSTRAINT `fk_anomaly_target` FOREIGN KEY (`target_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='이상탐지 — 탐지만으로는 제재하지 않고 검토 대상으로만 분류';

-- ---------------------------------------------------------------------
-- 4) 장애 구제
--
--  성공 처리가 아니라 분모에서 제외하는 중립 처리다. 성공으로 만들면 장애를 겪지 않은
--  사람과의 형평이 깨지고, 실패로 두면 서비스 책임을 사용자가 진다.
-- ---------------------------------------------------------------------
CREATE TABLE `outage_reliefs` (
    `id`             BINARY(16) NOT NULL COMMENT 'UUIDv7',
    `period_start`   DATETIME(3) NOT NULL,
    `period_end`     DATETIME(3) NOT NULL,
    `scope`          VARCHAR(30) NOT NULL COMMENT 'ALL / VERIFY_TYPE',
    `operator_id`    BINARY(16) NOT NULL,
    `affected_count` INT NULL COMMENT '적용 전에 미리 보여주고 확인받은 영향 판정 건수',
    `applied_at`     DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    -- 판정 건이 구제 범위에 드는지 역조회 — 인증 모듈이 사용한다.
    KEY `ix_relief_period` (`period_start`, `period_end`),
    CONSTRAINT `fk_relief_operator` FOREIGN KEY (`operator_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='장애 구제 — 성공 처리가 아니라 분모에서 제외';

-- ---------------------------------------------------------------------
-- 5) 신고 — 검토 결과를 담을 수 있게
--
--  구 review_status(PENDING/VALID/INVALID)는 "신고가 유효한가"를 물었다. 그런데 스펙의
--  종결 상태는 "무엇을 했는가"다 — 문제없음 종결과 제재 진행은 후속 조치가 달라 구분해야 한다.
--  접수 측(신고 API·차단)은 다음 스택에서 맞춘다.
-- ---------------------------------------------------------------------
ALTER TABLE `reports`
    ADD COLUMN `status` ENUM('PENDING','RESOLVED_NO_ACTION','RESOLVED_SANCTIONED')
        NOT NULL DEFAULT 'PENDING' AFTER `review_status`,
    ADD COLUMN `resolved_at` DATETIME(3) NULL COMMENT '운영자 종결 시각 — 처리 기한은 없다'
        AFTER `status`;

-- 구 값을 새 축으로 옮긴다. VALID 는 "유효한 신고"였을 뿐 제재까지 갔는지는 알 수 없으므로
-- 종결 상태를 임의로 만들지 않고 PENDING 으로 두어 운영자가 다시 판단하게 한다.
UPDATE `reports` SET `status` = 'RESOLVED_NO_ACTION' WHERE `review_status` = 'INVALID';

-- 검토 큐의 주 인덱스 — 미검토 건을 접수 순으로 꺼낸다.
CREATE INDEX `ix_reports_status` ON `reports` (`status`, `created_at`);

-- ---------------------------------------------------------------------
-- 6) 신고 스냅샷
--
--  원본이 수정·삭제돼도 이 값으로 검토하므로 **절대 갱신하지 않는다**.
--  적재는 방 내부 모듈이 하고 백오피스는 읽기만 한다.
-- ---------------------------------------------------------------------
CREATE TABLE `report_snapshots` (
    `report_id` BINARY(16) NOT NULL,
    `payload`   JSON NOT NULL
                COMMENT '대상 콘텐츠·이미지 키·수정 상태, 소속 챌린지, 피신고자 프로필, 발생 화면, 접수 시각',
    PRIMARY KEY (`report_id`),
    CONSTRAINT `fk_report_snapshots_report`
        FOREIGN KEY (`report_id`) REFERENCES `reports` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='신고 시점 스냅샷 — 갱신하지 않는다';
