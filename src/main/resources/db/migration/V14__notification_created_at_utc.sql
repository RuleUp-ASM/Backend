-- ======================================================================
-- 알림 적재 시각을 UTC 벽시계로 되돌린다 (QA NOTI-13 · NOTI-14 · WAT-11)
--
-- notifications.created_at 은 JdbcTemplate 이 java.sql.Timestamp 로 적어 왔다.
-- 그 렌더링은 접속 타임존을 따르므로 운영(KST 접속)에서는 KST 벽시계가 들어갔다.
-- 반면 읽는 쪽은 JPA 의 Instant 매핑이라 같은 값을 UTC 로 해석한다 — 알림함
-- createdAt 이 실제보다 정확히 9시간 앞서 보인 이유다.
--
-- 적는 쪽은 코드에서 LocalDateTime(UTC) 으로 고쳤다. 여기서는 이미 적힌 행의
-- 시각을 그만큼 되돌린다. 뺄 값을 9시간으로 박지 않고 접속 자신의 오프셋에서
-- 구하는 이유는, 접속이 UTC 인 환경(로컬·테스트)에서는 애초에 어긋나지 않았기
-- 때문이다 — 거기서는 오프셋이 0 이라 이 문장이 아무것도 바꾸지 않는다.
--
-- pushed_at 은 JPA 가 적으므로 이미 UTC 다. 건드리지 않는다.
-- ======================================================================

SET @offset_seconds = TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW());

UPDATE `notifications`
   SET `created_at` = `created_at` - INTERVAL @offset_seconds SECOND
 WHERE @offset_seconds <> 0;
