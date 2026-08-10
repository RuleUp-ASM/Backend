-- =====================================================================
-- V6: 하드 삭제 후 인증 원본 보존을 위한 소프트 참조 전환 (백엔드 테크 스펙 4-1)
--  방 자동 삭제 시 잔디(방 데이터)는 지우되 인증 기록 원본·이의 기록은 보존해야 한다.
--  운영 테이블 FK 를 해제해 challenge_id/challenge_member_id 를 이력 테이블 기준
--  소프트 참조로 유지한다(챌린지 행이 사라져도 원본 행 잔존 가능).
-- =====================================================================
ALTER TABLE `VerificationDaily` DROP FOREIGN KEY `fkVerificationDailyMember`;
ALTER TABLE `Objection` DROP FOREIGN KEY `fkObjectionChallenge`;
