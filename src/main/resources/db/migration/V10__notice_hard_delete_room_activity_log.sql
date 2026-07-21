-- 공지 소프트삭제 → 물리삭제 전환 + 방 내부 기록 활동 로그(RoomActivityLog).
-- V9는 이미 스테이징에 적용된 마이그레이션이라 수정하면 Flyway 체크섬 검증이 깨진다.
-- 따라서 V9는 원본 그대로 두고, 스키마 변경분만 이 V10으로 분리한다.

-- Notice: 소프트삭제(deletedAt) 컬럼/인덱스 제거 → 물리 삭제로 전환(방 관리 부담 완화).
-- 인덱스가 deletedAt을 참조하므로 인덱스 → 컬럼 → 인덱스 순으로 한 번에 재구성한다.
ALTER TABLE `Notice`
    DROP KEY `ixNoticeChallengePinned`,
    DROP COLUMN `deletedAt`,
    ADD KEY `ixNoticeChallengePinned` (`challengeId`,`pinned`,`createdAt`);

-- 방 내부 기록 활동 로그(append-only). 챌린지/유저 FK 없음 → 방(챌린지) 하드삭제 후에도 감사 로그로 생존.
CREATE TABLE `RoomActivityLog` (
  `id` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `actorId` binary(16) DEFAULT NULL,
  `entityType` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `entityId` binary(16) DEFAULT NULL,
  `action` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload` text COLLATE utf8mb4_unicode_ci,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ixRoomLogChallengeCreated` (`challengeId`,`createdAt`),
  KEY `ixRoomLogEntity` (`entityType`,`entityId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
