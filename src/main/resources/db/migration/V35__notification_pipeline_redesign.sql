-- =====================================================================
-- V35: 알림 파이프라인 재설계 — 백엔드 테크 스펙 3절
--
--  V27 은 「적재 본체 + 발송 시도 기록 + 중복 제어 + 유형별 토글」 4테이블 구조였다.
--  재설계로 **테이블이 3개**가 된다. 없앤 것과 그 근거는 이렇다.
--
--   · notification_deliveries → 삭제. 인플라이트 상태는 SQS 가 가진다. 대기 행을 DB 로 폴링하면
--     인덱스 (sent_at, scheduled_at, id) 가 대기 수천 건을 위해 **2,500만 엔트리를 영구히**
--     끌고 다닌다. MySQL 에 부분 인덱스가 없어 피할 방법이 없고, 파기 DELETE 도 이 인덱스를
--     같이 갱신한다. 발송 로그는 CloudWatch 구조화 로그로 간다.
--   · notification_dedup   → 삭제. 억제 기록을 notifications.pushed_at 으로 대체한다.
--     **대가를 안다** — 구 조건부 UPSERT 는 원자적이라 동시 판정에서도 중복이 새지 않았는데,
--     조회+갱신 2단계가 되면서 누수 가능성이 생겼다. 억제는 완화 장치라 수용한다.
--   · notification_settings(유형별) → user_notification_settings(마스터+그룹 3종)로 교체.
--     공통 #20 이 「마스터 + 그룹 3종」으로 종결됐다.
--
--  대신 notifications 가 스스로 네 가지를 더 진다 — 탭 · 토글 그룹 스냅샷 · 두 층의 키 ·
--  발송 성공 시각. read_at 과 deleted_at 은 사라진다: 읽음은 커서 두 개로 표현되고
--  개별 삭제는 정책에서 폐지됐다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) notifications — 컬럼 추가
--
--  deeplink 를 200 → 255 로 넓힌다. 재설계로 딥링크가 렌더링 완료 문자열이 됐다.
-- ---------------------------------------------------------------------
ALTER TABLE `notifications`
    ADD COLUMN `tab`          TINYINT      NOT NULL DEFAULT 0
        COMMENT '0 알림 / 1 운영자 공지. 읽음 커서도 탭별로 따로' AFTER `user_id`,
    ADD COLUMN `toggle_group` VARCHAR(10)  NOT NULL DEFAULT 'ACCOUNT'
        COMMENT 'ACCOUNT/CHALLENGE/MARKETING/NONE — 발송 판정 입력 + 감사 스냅샷' AFTER `type`,
    ADD COLUMN `challenge_id` BINARY(16)   NULL
        COMMENT '카운터 귀속 전용. 감시자 통지는 방 멤버가 아니라 NULL' AFTER `toggle_group`,
    ADD COLUMN `dedup_key`    VARCHAR(160) NULL
        COMMENT '발행 멱등(1회성). UNIQUE 가 INSERT 단계에서 막는다',
    ADD COLUMN `suppress_key` VARCHAR(160) NULL
        COMMENT '인터벌 억제(반복성). 6개 타입만 사용, 나머지는 NULL',
    ADD COLUMN `pushed_at`    DATETIME(3)  NULL
        COMMENT '푸시 성공 시각. 억제 판정의 기준 — created_at 이 아니다',
    MODIFY COLUMN `deeplink` VARCHAR(255) NULL COMMENT '렌더링 완료된 문자열';

-- ---------------------------------------------------------------------
-- 2) 백필 — 분류(A/B/C)를 토글 그룹으로
--
--  분류에서 그대로 옮기지 않고 **타입 코드로 판정**한다. A 는 계정과 「그룹 없음」이 섞여 있어
--  (리마인더가 구조상 B 였다) 분류만 보면 리마인더가 CHALLENGE 로 들어간다.
-- ---------------------------------------------------------------------
UPDATE `notifications`
   SET `toggle_group` = CASE
        WHEN `type` IN ('VERIFICATION_RESULT', 'CONSECUTIVE_FAILURE_WARNING',
                        'CHALLENGE_LIFECYCLE', 'WATCHER_INVITATION_EXPIRED', 'TIER_CHANGED',
                        'TIER_BOUNDARY_NEAR', 'PENALTY_FAILURE_SHARED', 'WATCHER_REACTION')
             THEN 'CHALLENGE'
        WHEN `type` = 'MARKETING'                             THEN 'MARKETING'
        WHEN `type` IN ('ROUTINE_REMINDER', 'ANNOUNCEMENT')   THEN 'NONE'
        ELSE 'ACCOUNT'
   END;

-- 카운터 귀속 — 구 target_key 가 챌린지 UUID 였던 타입만 옮긴다.
-- 감시자 통지(PENALTY_FAILURE_SHARED)는 target_key 가 통지 ID 라 제외한다.
UPDATE `notifications`
   SET `challenge_id` = UNHEX(REPLACE(`target_key`, '-', ''))
 WHERE `target_key` REGEXP '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
   AND `type` IN ('VERIFICATION_RESULT', 'CONSECUTIVE_FAILURE_WARNING', 'CHALLENGE_LIFECYCLE',
                  'WATCHER_INVITATION_EXPIRED', 'ROUTINE_REMINDER', 'CHALLENGE_IMAGE_REMOVED',
                  'PERMISSION_REGRANT_REQUIRED');

-- 이미 적재된 행은 dedup_key 가 NULL 이다. MySQL 의 UNIQUE 는 NULL 을 중복으로 보지 않으므로
-- 여러 행이 그대로 공존한다 — 소급해서 키를 만들면 없던 멱등 관계를 발명하게 된다.

-- ---------------------------------------------------------------------
-- 3) 인덱스 교체
--
--  ⚠️ 새 인덱스를 **먼저** 만든다. (user_id, ...) 로 시작하는 인덱스가 하나도 없는 순간이
--  생기면 fk_notifications_user 가 자기 인덱스를 자동 생성해 이름 없는 잔재로 남는다.
-- ---------------------------------------------------------------------

-- 알림 센터 목록. 등치(user_id, tab)가 앞, 범위·정렬(id)이 뒤 — 쿼리 모양이 순서를 정했다.
-- tab 을 빼면 공지 탭이 50건을 채우려고 6개월치 900행을 끝까지 스캔한다.
CREATE INDEX `idx_notifications_inbox` ON `notifications` (`user_id`, `tab`, `id`);

-- 인터벌 억제 판정. 세 컬럼이 전부 들어 있어 커버링 인덱스다 — 테이블을 읽지 않는다.
CREATE INDEX `idx_notifications_suppress` ON `notifications` (`user_id`, `suppress_key`, `pushed_at`);

-- 6개월 파기 배치.
CREATE INDEX `idx_notifications_purge` ON `notifications` (`created_at`);

-- 발행 멱등. NULL 은 중복으로 보지 않으므로 키 없는 발행은 그대로 여러 행이 된다.
CREATE UNIQUE INDEX `uq_notifications_dedup` ON `notifications` (`dedup_key`);

DROP INDEX `ix_notifications_inbox` ON `notifications`;
DROP INDEX `ix_notifications_cleanup` ON `notifications`;

-- ---------------------------------------------------------------------
-- 4) notifications — 구 컬럼 제거
--
--  deleted_at 이 구 인덱스의 선행 컬럼이었으므로 인덱스를 먼저 지운 뒤에야 지울 수 있다.
-- ---------------------------------------------------------------------
ALTER TABLE `notifications`
    DROP COLUMN `category`,
    DROP COLUMN `target_key`,
    DROP COLUMN `read_at`,
    DROP COLUMN `deleted_at`;

-- ---------------------------------------------------------------------
-- 5) 설정 — 유형별 토글을 마스터 + 그룹 3종 + 읽음 커서 2개로
--
--  백필하지 않는다. **행이 없으면 전부 ON, 읽음 커서가 NULL 이면 전부 미읽음**으로 해석한다.
--  구 유형별 값을 그룹으로 접으면 매핑이 임의라, 사용자가 끈 적 없는 그룹이 꺼진 상태가 된다.
-- ---------------------------------------------------------------------
DROP TABLE `notification_settings`;

CREATE TABLE `user_notification_settings` (
    `user_id`                   BINARY(16)  NOT NULL,
    `push_enabled`              TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '마스터 — 가장 제한적인 것이 이긴다',
    `group_account`             TINYINT(1)  NOT NULL DEFAULT 1,
    `group_challenge`           TINYINT(1)  NOT NULL DEFAULT 1,
    `group_marketing`           TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '약관 수신 동의와 연동',
    `last_read_notification_id` BINARY(16)  NULL COMMENT 'tab=0 읽음 지점. 서버는 카운트를 세지 않는다',
    `last_read_announcement_id` BINARY(16)  NULL COMMENT 'tab=1 읽음 지점',
    `updated_at`                DATETIME(3) NOT NULL,
    -- 유저당 1행이고 접근이 PK 단건 조회뿐이라 추가 인덱스가 필요 없다.
    PRIMARY KEY (`user_id`),
    CONSTRAINT `fk_user_notification_settings_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='알림 설정 — 마스터 1 + 그룹 3 + 읽음 커서 2. 행이 없으면 전부 ON';

-- ---------------------------------------------------------------------
-- 6) 발송 시도·중복 제어 테이블 제거
-- ---------------------------------------------------------------------
DROP TABLE `notification_deliveries`;
DROP TABLE `notification_dedup`;

-- ---------------------------------------------------------------------
-- 7) 아웃박스에 남은 알림 발행분 정리
--
--  적재가 도메인 트랜잭션 안으로 들어와 알림은 더 이상 아웃박스를 거치지 않는다. 남아 있던
--  미처리 행은 핸들러가 사라져 영원히 스윕에 걸리므로 처리 완료로 닫는다. 아웃박스 테이블
--  자체는 남는다 — 제재 자동 탈퇴 등 다른 발행이 계속 쓴다.
-- ---------------------------------------------------------------------
UPDATE `outbox_messages`
   SET `processed_at` = NOW(3),
       `last_error`   = 'RETIRED_V35_NOTIFICATION_INLINE'
 WHERE `type` = 'NOTIFICATION' AND `processed_at` IS NULL;
