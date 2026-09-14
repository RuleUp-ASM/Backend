-- =====================================================================
-- 포기한 메시지에서 `processed_at` 을 걷는다 (알림 공통 5-1 · 인증 공통 5-7)
--
-- V49 는 예전에 포기로 닫힌 행에 `dead_lettered_at` 을 새겼지만 `processed_at` 은 그대로 뒀다.
-- 두 시각이 모두 차 있는 행은 되살릴 수 없다.
--
--   redrive() 가 `dead_lettered_at` 을 지운다 → 죽은 목록에서 사라진다
--   그런데 `processed_at` 이 남아 `isPending()` 이 여전히 false → 스윕이 집지 않는다
--   `dedup_key` 는 그대로라 같은 사건의 재적재도 이 행으로 흡수된다
--   ⇒ 목록에도 없고 발행도 안 되는, **되살릴 수조차 없는** 상태
--
-- 포기는 「발행되지 않았다」는 뜻이므로 `processed_at` 이 차 있는 것 자체가 거짓이다. 걷어낸다.
-- 정상 발행분은 `dead_lettered_at` 이 비어 있어 건드리지 않는다.
-- =====================================================================

UPDATE `outbox_messages`
   SET `processed_at` = NULL
 WHERE `dead_lettered_at` IS NOT NULL
   AND `processed_at` IS NOT NULL;
