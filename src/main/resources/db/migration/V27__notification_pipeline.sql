-- =====================================================================
-- V27: 알림 파이프라인 — 알림 및 알림함 공통 5-3 · 백엔드 4-1
--
--  지금까지 Notification 테이블 하나에 type enum 만 있었다. 그래서 분류 체계도, 발송
--  시도 기록도, 야간 보류도, 중복 제어도 담을 자리가 없었다.
--
--  핵심 불변식 하나로 설계가 갈린다 — **모든 알림은 푸시 발송 여부와 무관하게 알림함에
--  적재되며, 필수(A) 알림의 법적 고지는 그 적재 시점에 성립한다.** 그래서
--
--    notifications            → 적재 본체. created_at 이 고지 성립 시각이라 불변이다
--    notification_deliveries  → 푸시 시도 기록. 적재와 분리돼 있어 푸시가 실패해도 고지는 유효하다
--
--  두 테이블을 나눠야 "푸시는 실패했지만 고지는 성립했다"를 표현할 수 있다.
--
--  type 을 ENUM 이 아니라 VARCHAR 로 두는 이유는 페이지2 공지·댓글 5종을 DDL 없이
--  합류시키기 위함이다. category 는 레지스트리에서 복사해 저장한다 — 분류가 나중에
--  바뀌어도 이미 발행된 알림의 법적 성격은 그대로 남아야 한다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 알림함 적재 본체
-- ---------------------------------------------------------------------
CREATE TABLE `notifications` (
    `id`         BINARY(16) NOT NULL COMMENT 'UUIDv7',
    `user_id`    BINARY(16) NOT NULL,
    -- ENUM 이 아니다 — 타입 추가에 DDL 이 필요 없게 한다.
    `type`       VARCHAR(40) NOT NULL,
    -- A 필수 / B 기능 / C 마케팅 — 야간·토글·중복 분기의 유일한 근거.
    `category`   CHAR(1) NOT NULL,
    `title`      VARCHAR(100) NOT NULL,
    -- 민감정보를 담지 않는다. 푸시는 잠금화면에 그대로 뜨므로 제재 사유 상세는 앱 안에서 본다.
    `body`       VARCHAR(500) NOT NULL,
    `deeplink`   VARCHAR(200) NULL,
    `target_key` VARCHAR(100) NULL COMMENT '중복 제어·딥링크의 대상 식별자',
    -- 고지 성립 시각 — 불변. 재발송해도 갱신하지 않는다.
    `created_at` DATETIME(3) NOT NULL,
    `read_at`    DATETIME(3) NULL COMMENT '페이지1은 뱃지를 쓰지 않으나 필드는 미리 둔다',
    `deleted_at` DATETIME(3) NULL COMMENT '유저 개별 삭제 — 소프트. 고지 기록 자체는 남는다',
    PRIMARY KEY (`id`),
    -- 알림함 커서 페이징의 주 인덱스. MySQL 에는 부분 인덱스가 없어 WHERE deleted_at IS NULL 로
    -- 줄일 수 없으므로 deleted_at 을 선행 컬럼으로 넣어 같은 효과를 낸다.
    KEY `ix_notifications_inbox` (`user_id`, `deleted_at`, `created_at` DESC, `id` DESC),
    -- 6개월 경과분 정리 배치. 전체 스캔을 막으려고 별도로 둔다.
    KEY `ix_notifications_cleanup` (`created_at`, `id`),
    CONSTRAINT `fk_notifications_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='알림함 적재 본체 — created_at 이 법적 고지 성립 시각';

-- ---------------------------------------------------------------------
-- 2) 푸시 발송 시도 기록
--
--  야간 보류를 scheduled_at 으로 표현하고 즉시 발송 건도 scheduled_at = now 로 둬서
--  하나의 경로로 통일한다. 보류 큐와 즉시 발송을 따로 만들면 아침 요약 배치가
--  즉시 발송분의 누락을 못 보게 된다.
-- ---------------------------------------------------------------------
CREATE TABLE `notification_deliveries` (
    `id`                BINARY(16) NOT NULL COMMENT 'UUIDv7',
    `notification_id`   BINARY(16) NOT NULL,
    `channel`           VARCHAR(10) NOT NULL COMMENT 'PUSH — SMS·이메일은 정책상 배제',
    `scheduled_at`      DATETIME(3) NOT NULL COMMENT '야간 보류분은 다음 08:00 KST, 즉시 발송은 now',
    `sent_at`           DATETIME(3) NULL COMMENT 'null 이면 미발송 — 보정 배치가 이 값으로 누락을 찾는다',
    `result`            VARCHAR(20) NULL COMMENT 'SUCCESS / FAILED / SUPPRESSED',
    `suppressed_reason` VARCHAR(30) NULL COMMENT 'TOGGLE_OFF / MUTED / DEDUP / NIGHT_MARKETING / NO_DEVICE',
    `error_code`        VARCHAR(50) NULL COMMENT 'FCM 오류 — 재시도하지 않고 기록만 한다',
    PRIMARY KEY (`id`),
    -- 아침 요약·보정 배치의 핵심. sent_at 을 선두에 두는 이유는 미발송 행이 전체의 극소수이기 때문이다.
    KEY `ix_deliveries_pending` (`sent_at`, `scheduled_at`, `id`),
    KEY `ix_deliveries_notification` (`notification_id`),
    CONSTRAINT `fk_deliveries_notification`
        FOREIGN KEY (`notification_id`) REFERENCES `notifications` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='푸시 발송 시도 — 적재와 분리돼 있어 푸시 실패가 고지를 무효화하지 않는다';

-- ---------------------------------------------------------------------
-- 3) 유형별 토글 — 구 단일행 설정을 유형별 행으로 교체
--
--  구 notification_settings 는 유저당 1행에 challenge_activity·room_activity 같은
--  뭉뚱그린 카테고리 컬럼을 두었다. 스펙은 알림 타입별 토글이므로 구조가 다르다.
--  행이 없으면 기본 ON 으로 해석하므로 백필하지 않는다 — 구 설정값을 옮기려면
--  카테고리를 타입으로 쪼개야 하는데, 그 매핑이 임의라 잘못 옮기면 사용자가 끈 적 없는
--  알림이 꺼진 상태가 된다. 전원 기본 ON 으로 시작하는 편이 안전하다.
-- ---------------------------------------------------------------------
DROP TABLE `notification_settings`;

CREATE TABLE `notification_settings` (
    `user_id`    BINARY(16) NOT NULL,
    -- 필수(A) 타입은 이 테이블에 들어올 수 없다 — 서비스가 거부한다.
    `type`       VARCHAR(40) NOT NULL,
    -- 0이면 푸시만 생략한다. 알림함 적재는 그대로다.
    `enabled`    TINYINT(1) NOT NULL,
    `updated_at` DATETIME(3) NOT NULL,
    -- 설정 화면은 유저 단위 전체 조회라 PK 선두 컬럼만으로 끝난다. 별도 인덱스가 필요 없다.
    PRIMARY KEY (`user_id`, `type`),
    CONSTRAINT `fk_notification_settings_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `chk_notification_settings_enabled` CHECK (`enabled` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='기능(B) 유형별 토글 — 행이 없으면 기본 ON';

-- ---------------------------------------------------------------------
-- 4) 챌린지별 음소거 — 유형별 토글과 AND 로 결합
-- ---------------------------------------------------------------------
CREATE TABLE `notification_mutes` (
    `user_id`      BINARY(16) NOT NULL,
    `challenge_id` BINARY(16) NOT NULL,
    `muted_at`     DATETIME(3) NOT NULL,
    -- 발송 분기에서 (user_id, challenge_id) 단건 조회만 하므로 추가 인덱스가 필요 없다.
    PRIMARY KEY (`user_id`, `challenge_id`),
    CONSTRAINT `fk_notification_mutes_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_notification_mutes_challenge`
        FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='챌린지별 음소거';

-- ---------------------------------------------------------------------
-- 5) 중복 제어 — 기본 24시간, 티어 경계만 1주
--
--  ⚠️ target_key 는 없으면 빈 문자열이다. NULL 을 쓰면 MySQL 유니크가 동작하지 않아
--     같은 알림이 여러 행으로 들어가고 중복 제어가 통째로 무력화된다.
-- ---------------------------------------------------------------------
CREATE TABLE `notification_dedup` (
    `user_id`      BINARY(16) NOT NULL,
    `type`         VARCHAR(40) NOT NULL,
    `target_key`   VARCHAR(100) NOT NULL DEFAULT '' COMMENT '없으면 빈 문자열 — NULL 금지',
    `last_sent_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`user_id`, `type`, `target_key`),
    CONSTRAINT `fk_notification_dedup_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='중복 제어 — 조건부 UPSERT 로 경합에서도 중복 발송이 안 나가게 한다';

-- ---------------------------------------------------------------------
-- 6) 구 Notification 테이블 정리
--
--  타입 집합이 통째로 바뀌었다(구 13종 → 레지스트리 21종). 매핑이 1:1이 아니라
--  옮기면 분류가 틀린 채로 남고, 분류는 야간 보류·토글·중복의 유일한 근거라
--  틀린 값이 그대로 동작이 된다. 알림함은 신규 구축이므로 백필하지 않고 버린다.
-- ---------------------------------------------------------------------
DROP TABLE `Notification`;
