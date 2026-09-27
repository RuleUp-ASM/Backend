-- Phase 1에서 제외된 챌린지 방장(사용자)의 공지·읽음·댓글 저장소를 제거한다.
-- V4는 이미 적용된 베이스라인이므로 수정하지 않고 후속 마이그레이션으로 정리한다.
-- 운영자 공지 announcements, 인앱 notifications, 감시자 watcher_notices,
-- 범용 활동 로그 RoomActivityLog는 별도 도메인이므로 유지한다.
-- 기존 방 공지·댓글 데이터도 삭제된다. 재도입은 새 마이그레이션으로 진행한다.
DROP TABLE IF EXISTS `room_comments`;
DROP TABLE IF EXISTS `NoticeRead`;
DROP TABLE IF EXISTS `Notice`;
