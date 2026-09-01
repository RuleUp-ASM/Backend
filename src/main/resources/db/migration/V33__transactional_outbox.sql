-- =====================================================================
-- V33: 트랜잭셔널 아웃박스 — 알림 공통 5-1 · 백오피스 공통 5-1
--
--  지금까지 도메인 → 알림 사이에 두 가지 구멍이 있었다.
--
--   ① REQUIRES_NEW 로 알림을 먼저 커밋했다. 도메인 트랜잭션이 뒤에 롤백되면
--      **제재는 없는데 제재 고지만 남는다.** 알림 스펙이 "제재가 롤백됐는데 고지만
--      나가면 더 안 된다"고 못박은 바로 그 상태다.
--   ② 커밋 뒤 처리를 JVM 메모리의 afterCommit 콜백에 맡겼다. DB 커밋 직후 서버가
--      죽으면 **필수(A) 고지와 전 챌린지 자동 탈퇴가 통째로 사라진다.** 재시작해도
--      주울 근거가 어디에도 없다.
--
--  둘 다 "발행 의사"를 도메인 트랜잭션과 같은 커밋에 적어 두면 사라진다. 이 테이블이
--  그 자리다 — 도메인 트랜잭션 안에서 행 하나를 INSERT 하고, 발행은 커밋 이후
--  디스패처가 이 행을 읽어서 한다.
--
--    · 도메인이 롤백되면 이 행도 함께 롤백된다  → ①이 닫힌다
--    · 커밋됐으면 행이 남아 스윕이 반드시 줍는다 → ②가 닫힌다
--
--  큐(SQS·Kafka)를 쓰지 않는 이유는 같은 커밋에 넣을 수 없기 때문이다. 외부 큐로
--  보내는 순간 "DB 는 커밋됐는데 큐 전송은 실패" 조합이 다시 생긴다.
-- =====================================================================

CREATE TABLE `outbox_messages` (
    `id`           BINARY(16)   NOT NULL COMMENT 'UUIDv7',
    -- 핸들러 라우팅 키. ENUM 이 아니라 VARCHAR 인 이유는 알림 type 과 같다 —
    -- 새 발행 종류를 DDL 없이 붙일 수 있어야 한다.
    `type`         VARCHAR(40)  NOT NULL COMMENT 'NOTIFICATION / SANCTION_LEAVE ...',
    -- 발행에 필요한 값 전부를 담은 JSON. **엔티티 참조가 아니라 스냅샷**이다 —
    -- 디스패처가 도는 시점에 원본이 바뀌어 있어도 발행 내용은 커밋 당시 그대로여야 한다.
    `payload`      JSON         NOT NULL,
    -- 같은 사건을 두 번 적지 않기 위한 키. NULL 이면 중복 제어를 하지 않는다
    -- (MySQL 의 UNIQUE 는 NULL 을 중복으로 보지 않으므로 그대로 여러 행이 들어간다).
    `dedup_key`    VARCHAR(160) NULL,
    `created_at`   DATETIME(3)  NOT NULL,
    -- 이 시각 이후에만 집는다. 재시도 백오프를 여기에 적는다.
    `available_at` DATETIME(3)  NOT NULL,
    -- NULL 이면 미처리 — 스윕이 이 값으로 남은 일을 찾는다.
    `processed_at` DATETIME(3)  NULL,
    `attempts`     INT          NOT NULL DEFAULT 0,
    `last_error`   VARCHAR(500) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_outbox_dedup` (`dedup_key`),
    -- 스윕의 유일한 접근 경로. processed_at 을 선두에 둬서 미처리 행만 스캔한다.
    -- 처리된 행이 아무리 쌓여도 스윕 비용이 늘지 않는다.
    KEY `ix_outbox_pending` (`processed_at`, `available_at`),
    -- 정리 배치용 — 처리 완료 후 보관 기간이 지난 행을 지운다.
    KEY `ix_outbox_processed` (`processed_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='도메인 트랜잭션과 같은 커밋에 적는 발행 대기함';
