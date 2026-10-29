-- ======================================================================
-- 이의 접수 당시의 실패 설명을 이의 행에 남긴다 — 관리자 이의 내역(Backend #361)
--
-- 이의는 확정 전(실패 예정)에 받고 접수 즉시 인용돼 판정 행의 failureReason 이 지워진다.
-- verification_failure_details 는 확정된 실패에만 생기므로 인용된 이의에는 행이 없다.
-- 그래서 「무엇에 이의를 냈는지」가 어디에도 남지 않았다 — 유저가 신청 화면에서 본 설명을 그대로 적어 둔다.
-- 이 파일 이전에 접수된 이의는 NULL 이다(되살릴 원천이 없다).
--
-- acceptedAt 인덱스: 관리자 목록이 전체 이의를 최신순 커서로 읽는다. 기존 인덱스는 userId 가 앞이라 쓸 수 없다.
-- ======================================================================

ALTER TABLE `verification_appeals`
    ADD COLUMN `failureReason` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL
        COMMENT '접수 당시 실패 사유 코드. 인용되면 판정 행의 사유가 지워져 여기만 남는다',
    ADD COLUMN `failureSummary` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL
        COMMENT '접수 당시 유저에게 보인 판정 근거 한 줄',
    ADD COLUMN `failureExpected` json DEFAULT NULL COMMENT '접수 당시 기준값',
    ADD COLUMN `failureActual` json DEFAULT NULL COMMENT '접수 당시 실제값',
    ADD KEY `idx_verification_appeals_accepted` (`acceptedAt`, `id`),
    ALGORITHM = INPLACE, LOCK = NONE;
