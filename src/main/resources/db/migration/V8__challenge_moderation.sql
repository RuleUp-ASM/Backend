-- 경로: src/main/resources/db/migration/V8__challenge_moderation.sql
-- CLAUDE.md §6.1/§6.2/§5.1 — 챌린지 모더레이션 게이트.
--  1) moderationStatus(가시성 게이트) + 거절 1시간 수정창(fixDeadline) 컬럼 추가.
--  2) status enum을 API 계약(COMPLETED)에 맞춰 정렬: DRAFT 제거, ENDED→COMPLETED.
--  3) §6.5 멤버 조회 인덱스 보강.
-- (DB 컬럼 네이밍은 기존 Challenge 테이블 컨벤션(camelCase)을 따른다.)

-- ===== 1) 모더레이션 컬럼 =====
ALTER TABLE Challenge
    ADD COLUMN moderationStatus    ENUM('PENDING_REVIEW','APPROVED','REJECTED')
                                   NOT NULL DEFAULT 'PENDING_REVIEW' AFTER status,
    ADD COLUMN moderationDecidedAt DATETIME(6) NULL AFTER moderationStatus,
    -- REJECTED 시 1시간 수정창 마감 시각(§5.1). OWNER 응답 fixDeadline 근거.
    ADD COLUMN fixDeadline         DATETIME(6) NULL AFTER moderationDecidedAt;

-- 기존 챌린지는 게이트 도입 이전 데이터 → 타인에게 계속 보이도록 APPROVED 백필
-- (PENDING_REVIEW 기본값이면 기존 공개 챌린지가 일제히 숨겨지는 사고가 난다).
UPDATE Challenge SET moderationStatus = 'APPROVED', moderationDecidedAt = createdAt;

-- ===== 2) status enum 정렬 (ENDED→COMPLETED, DRAFT 제거) =====
-- 파괴적 변경: 먼저 두 값을 모두 가진 과도기 enum으로 넓힌 뒤 데이터를 옮기고, 최종 enum으로 좁힌다.
ALTER TABLE Challenge
    MODIFY status ENUM('DRAFT','RECRUITING','ACTIVE','ENDED','COMPLETED')
                  NOT NULL DEFAULT 'RECRUITING';

UPDATE Challenge SET status = 'COMPLETED'  WHERE status = 'ENDED';
UPDATE Challenge SET status = 'RECRUITING' WHERE status = 'DRAFT';

ALTER TABLE Challenge
    MODIFY status ENUM('RECRUITING','ACTIVE','COMPLETED')
                  NOT NULL DEFAULT 'RECRUITING';

-- ===== 3) §6.5 멤버 조회 인덱스 =====
-- 멤버 목록(challengeId+status), 내 챌린지/진행률(userId+status) 핫 조회 대비.
CREATE INDEX ixMemberChallengeStatus ON ChallengeMember (challengeId, status);
CREATE INDEX ixMemberUserStatus      ON ChallengeMember (userId, status);
