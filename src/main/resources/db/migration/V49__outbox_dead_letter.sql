-- =====================================================================
-- 아웃박스의 <b>포기</b>를 처리 완료와 구분한다 (알림 공통 5-1 · 인증 공통 5-7)
--
-- 지금까지 재시도 상한(5회)을 넘긴 메시지는 `processed_at` 을 찍고 닫았다. 스윕이 같은 행을
-- 영원히 다시 집어 뒤에 쌓인 정상 건까지 굶는 것을 막으려던 것인데, 대가가 컸다.
--
--   ① **발행된 건과 포기한 건이 같은 모양이 된다.** `processed_at IS NOT NULL` 로는 「나갔다」와
--      「끝내 못 나갔다」를 구분할 수 없다. 감시자 통지·강퇴·감점이 조용히 사라져도 집계상으로는
--      정상 처리로 보인다.
--   ② **재적재가 영구히 막힌다.** `dedup_key` 행은 그대로 남으므로, 같은 사건을 다시 적재하려는
--      호출이 「이미 있다」로 건너뛴다. 수동 복구 경로가 없다.
--   ③ 14일 뒤 정리 배치가 그 행을 지운다 — 사고 조사의 근거까지 사라진다.
--
-- 그래서 포기를 별도 시각으로 남긴다. 스윕은 이 값이 있는 행을 집지 않으므로 ①의 굶림은
-- 그대로 막히고, 운영은 이 컬럼 하나로 「죽은 메시지」를 찾아 되살릴 수 있다.
-- =====================================================================

ALTER TABLE `outbox_messages`
    ADD COLUMN `dead_lettered_at` DATETIME(3) NULL
        COMMENT '재시도 상한을 넘겨 포기한 시각. 처리 완료(processed_at)와 구분한다 — 이 값이 있으면 발행되지 않았다'
        AFTER `processed_at`;

-- 죽은 메시지 조회 전용. 정상 경로에서는 비어 있어야 하는 인덱스라 비용이 거의 없다.
CREATE INDEX `ix_outbox_dead_lettered` ON `outbox_messages` (`dead_lettered_at`);

-- 과거에 「포기」로 닫힌 행은 되살릴 근거가 없다. attempts 가 상한에 닿았고 오류가 남아 있는
-- 행만 포기로 다시 표시한다 — 정상 발행분은 attempts 가 상한 미만이거나 last_error 가 비어 있다.
UPDATE `outbox_messages`
   SET `dead_lettered_at` = `processed_at`
 WHERE `processed_at` IS NOT NULL
   AND `attempts` >= 5
   AND `last_error` IS NOT NULL;
