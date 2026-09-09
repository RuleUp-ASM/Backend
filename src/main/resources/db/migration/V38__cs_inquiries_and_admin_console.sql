-- =====================================================================
-- V38: CS 문의 + 운영자 콘솔 확정 계약 — 백오피스 공통 5-2-1(2026-09-09) · 앱 운영 정책 § 5
--
--  백오피스 공통이 「본 문서에 CS 가 아예 없다」고 적고 관리자 쪽 계약만 §5-2-1 B 에 정의했다.
--  접수 규칙의 원본은 앱 운영 정책 § 5 다 — 카테고리 6종 + 본문 2단계, 답변 등록이 곧 종결,
--  재문의 없음. 여기서 그 계약을 담을 자리를 만든다.
--
--  나머지는 전용 콘솔(RuleUp-ASM/AdminPage)이 요구하는 컬럼들이다. 새 테이블은 inquiries
--  하나뿐이고 나머지는 이미 있는 테이블에 값을 담을 자리를 붙이는 수준이다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) CS 문의
--
--  **답변 등록이 곧 종결이다.** 상태를 4종으로 늘리자는 제안이 정책 미결로 남아 있으나
--  § 5.4 표는 접수됨 → 답변 완료 둘뿐이고 그 사이 전이가 없다. 실제로 있지도 않은 상태를
--  먼저 만들면 클라이언트가 그 칩을 그리게 되므로 표대로 2종만 둔다.
--
--  **재문의 경로가 없다**(§ 5.5). 그래서 스레드 테이블이 아니라 문의 1행에 답변 컬럼을 붙인다 —
--  1:1 이 구조적으로 보장되면 「답변이 2개인 문의」라는 상태가 아예 생기지 않는다.
-- ---------------------------------------------------------------------
CREATE TABLE `inquiries` (
    `id`            BINARY(16)   NOT NULL COMMENT 'UUIDv7',
    -- 탈퇴해도 문의는 남는다(운영 기록). 다만 계정 행은 복원 대상이라 FK 는 그대로 건다.
    `user_id`       BINARY(16)   NOT NULL,
    -- 인증·판정 / 권한·기기 연동 / 챌린지·그룹 / 계정·로그인 / 신고·제재 / 오류·제안·기타
    `category`      VARCHAR(30)  NOT NULL COMMENT '현재 분류 — 운영자가 바꿀 수 있다',
    -- **변경 사실을 유저에게 노출하지 않는다**(§ 5.1). 그래서 유저 응답은 이 값이 아니라
    -- category 를 그대로 보여주고, 이 컬럼은 운영 통계(분류별 접수 비중)의 기준으로만 쓴다.
    `origin_category` VARCHAR(30) NOT NULL COMMENT '유저가 처음 고른 분류',
    `body`          VARCHAR(1000) NOT NULL COMMENT '10~1,000자',
    -- 최대 3장. 별도 테이블을 두지 않는 이유는 순서가 의미를 갖고 개별 행을 조회할 일이 없어서다.
    `image_urls`    JSON         NULL,
    -- 자동 첨부(§ 5.2) — 고지 후 필수라 토글이 없다. 1차 확인 항목이 이 값들에서 나온다.
    `app_version`   VARCHAR(20)  NULL,
    `os_version`    VARCHAR(30)  NULL,
    `device_model`  VARCHAR(60)  NULL,
    `error_log_id`  VARCHAR(64)  NULL COMMENT '최근 오류 로그 ID — 오류 문의의 1차 대조 키',
    `status`        ENUM('RECEIVED','ANSWERED') NOT NULL DEFAULT 'RECEIVED',
    `answer_text`   VARCHAR(2000) NULL,
    `answered_at`   DATETIME(3)  NULL,
    `answered_by`   BINARY(16)   NULL COMMENT '답변한 운영자',
    `created_at`    DATETIME(3)  NOT NULL,
    PRIMARY KEY (`id`),
    -- 관리자 큐 — 미답변을 접수 순으로. status 가 선두라 답변 완료분이 쌓여도 비용이 늘지 않는다.
    KEY `ix_inquiries_queue` (`status`, `created_at`),
    -- 내 문의 내역 · 하루 3건 상한 판정.
    KEY `ix_inquiries_user` (`user_id`, `created_at`),
    -- 분류별 접수 비중(§ 5.8) — 원본 분류로 센다.
    KEY `ix_inquiries_category` (`origin_category`, `created_at`),
    CONSTRAINT `fk_inquiries_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='CS 문의 — 답변 등록이 곧 종결이며 재문의 경로가 없다';

-- ---------------------------------------------------------------------
-- 2) 신고 종결 — 결정 축을 콘솔 계약에 맞춘다
--
--  §5-2-1 A #3 이 decision 을 NO_ACTION · MODERATION_REJECT **둘로** 확정했다. 제재와 폐쇄는
--  #4 · #6 이 근거 신고를 함께 종결시키므로 resolve 가 직접 낼 결정이 아니다.
--  기존 RESOLVED_SANCTIONED 는 그 경로(제재·폐쇄가 종결시킨 건)의 값으로 그대로 남는다.
-- ---------------------------------------------------------------------
ALTER TABLE `reports`
    MODIFY COLUMN `status`
        ENUM('PENDING','RESOLVED_NO_ACTION','RESOLVED_SANCTIONED','RESOLVED_MODERATION_REJECT')
        NOT NULL DEFAULT 'PENDING',
    -- 종결 메모. 신고 내용을 인용할 수 있어 유저에게는 어떤 경로로도 나가지 않는다.
    ADD COLUMN `resolution_note` VARCHAR(500) NULL AFTER `resolved_at`,
    ADD COLUMN `resolved_by` BINARY(16) NULL COMMENT '종결한 운영자' AFTER `resolution_note`;

-- ---------------------------------------------------------------------
-- 3) 이상탐지 검토 메모
--
--  reviewed_at · reviewer_id 는 V29 에 있는데 **채울 경로가 계약 표에 없었다**(§5-2-1 B).
--  검토했다는 사실만 남기면 다음 운영자가 왜 넘겼는지 알 수 없어 메모를 함께 받는다.
-- ---------------------------------------------------------------------
ALTER TABLE `anomaly_signals`
    ADD COLUMN `review_note` VARCHAR(500) NULL AFTER `reviewer_id`;

-- ---------------------------------------------------------------------
-- 4) 운영 공지 — 종류 · 예약 · 취소
--
--  §5-2-1 A #10 이 kind 4종을 확정했고 B 가 발행 이력 조회와 예약 취소를 새로 정의했다.
--  **이미 적재된 공지는 회수되지 않는다** — 취소는 팬아웃 전에만 의미가 있으므로
--  canceled_at 은 대기 중인 행에만 채워진다.
-- ---------------------------------------------------------------------
ALTER TABLE `announcements`
    ADD COLUMN `kind` VARCHAR(20) NOT NULL DEFAULT 'MAINTENANCE'
        COMMENT 'MAINTENANCE / INCIDENT / TERMS / SHUTDOWN' AFTER `body`,
    ADD COLUMN `deep_link` VARCHAR(200) NULL AFTER `kind`,
    ADD COLUMN `scheduled_at` DATETIME(3) NULL
        COMMENT 'null 이면 즉시. 미래면 그 시각 이후에 팬아웃된다' AFTER `created_at`,
    ADD COLUMN `canceled_at` DATETIME(3) NULL
        COMMENT '팬아웃 전 취소. 적재된 뒤에는 회수되지 않는다' AFTER `scheduled_at`;

-- 대기 스캔 조건이 셋으로 늘었다. 취소·예약분이 인덱스에서 걸러지도록 선두를 유지한다.
DROP INDEX `ix_announcements_pending` ON `announcements`;
CREATE INDEX `ix_announcements_pending`
    ON `announcements` (`fanned_out_at`, `canceled_at`, `scheduled_at`, `id`);
