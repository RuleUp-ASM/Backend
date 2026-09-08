-- =====================================================================
-- V37: 운영자 공지 원본 — 백엔드 4-1(ALL 스코프) · 공통 #8
--
--  공지는 알림 레코드로 흡수됐다(구 GET /api/v1/announcements 폐기). 다만 **전체 공지 1건이
--  약 2만 행으로 팬아웃**되므로 그 INSERT 를 관리자 요청 트랜잭션 안에 둘 수 없다 —
--  커넥션을 오래 잡고, 실패하면 전부 롤백된다.
--
--  그래서 요청은 이 테이블에 원본만 저장하고 즉시 응답한 뒤, 잡이 청크 단위로 팬아웃한다.
--  중단돼도 dedup_key(ANNOUNCEMENT:{user_id}:{announcement_id}) UNIQUE 덕분에 재개할 수 있다.
-- =====================================================================

CREATE TABLE `announcements` (
    `id`              BINARY(16)   NOT NULL COMMENT 'UUIDv7',
    `title`           VARCHAR(100) NOT NULL,
    `body`            VARCHAR(500) NOT NULL,
    `created_by`      BINARY(16)   NOT NULL COMMENT '발행한 운영자',
    `created_at`      DATETIME(3)  NOT NULL,
    -- NULL 이면 팬아웃 대기. 잡이 이 값으로 남은 일을 찾는다.
    `fanned_out_at`   DATETIME(3)  NULL,
    `recipient_count` INT          NOT NULL DEFAULT 0 COMMENT '실제로 적재된 행 수',
    PRIMARY KEY (`id`),
    -- 대기 행만 스캔한다. 완료된 공지가 아무리 쌓여도 잡 비용이 늘지 않는다.
    KEY `ix_announcements_pending` (`fanned_out_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='운영자 공지 원본 — 팬아웃은 잡이 한다';
