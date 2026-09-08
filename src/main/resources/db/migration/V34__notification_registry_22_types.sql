-- =====================================================================
-- V34: 알림 레지스트리 22종 정합 — 백엔드 테크 스펙 5-1
--
--  레지스트리를 코드 enum 으로 확정하면서 타입 코드가 스펙 표 기준으로 정렬됐다.
--  notifications.type 은 VARCHAR 라 DDL 변경이 필요 없지만, **적재된 값이므로
--  개명은 데이터 마이그레이션**이다(공통 8절).
--
--  바뀐 것은 하나뿐이다 — INACTIVITY_WITHDRAWAL → INACTIVE_WITHDRAWAL_NOTICE.
--  발행 지점이 아직 없어 실제 행은 0건일 가능성이 크지만, 남아 있다면 알림함에서
--  타입이 해석되지 않아(NotificationType.find 가 empty) 그 행만 조용히 분류를 잃는다.
--
--  ANNOUNCEMENT(운영자 공지) 추가에는 마이그레이션이 필요 없다. 새 타입이라 기존 행이 없고,
--  tab 컬럼과 팬아웃 경로는 다음 단계에서 들어온다.
-- =====================================================================

UPDATE `notifications`
   SET `type` = 'INACTIVE_WITHDRAWAL_NOTICE'
 WHERE `type` = 'INACTIVITY_WITHDRAWAL';

-- 유형별 토글·중복 제어 테이블에도 같은 값이 들어갈 수 있다. 두 테이블 모두 다음 단계에서
-- 사라지지만, 그 사이에 남은 행이 없는 타입을 가리키면 설정 화면이 유령 항목을 그린다.
UPDATE `notification_settings`
   SET `type` = 'INACTIVE_WITHDRAWAL_NOTICE'
 WHERE `type` = 'INACTIVITY_WITHDRAWAL';

UPDATE `notification_dedup`
   SET `type` = 'INACTIVE_WITHDRAWAL_NOTICE'
 WHERE `type` = 'INACTIVITY_WITHDRAWAL';
