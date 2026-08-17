-- Phase 2 이관(기능 스펙 6-2 #9·#10, 2026-08-12 범위 조정) — 공지·댓글 저장소 제거.
--
-- 스펙 권고는 "빈 테이블로 남겨 두기"였으나, 쓰지 않는 테이블은 스키마를 읽는 사람에게
-- 곧 돌아올 기능처럼 보이고 하드 삭제·통계 쿼리에 계속 끌려다닌다. 코드와 함께 지운다.
-- 재개 시에는 이 파일을 되돌리는 것이 아니라 새 마이그레이션으로 다시 만든다.
--
-- 삭제 순서는 FK 역순이다. NoticeRead → Notice, room_comments 는 자기 참조(parent_comment_id)라
-- 테이블째 드롭하면 순서 문제가 없다. RoomActivityLog 는 공지 CRUD 감사 전용이라 함께 제거한다.

DROP TABLE IF EXISTS `room_comments`;
DROP TABLE IF EXISTS `NoticeRead`;
DROP TABLE IF EXISTS `Notice`;
DROP TABLE IF EXISTS `RoomActivityLog`;

-- 알림 타입 enum 에서 NOTICE_CREATED·COMMENT_CREATED 를 지웠으므로 남은 행도 정리한다.
-- (Notification.type 은 @Enumerated(STRING) 이라 남겨두면 알림함 조회가 통째로 깨진다)
DELETE FROM `Notification` WHERE `type` IN ('NOTICE_CREATED', 'COMMENT_CREATED');
