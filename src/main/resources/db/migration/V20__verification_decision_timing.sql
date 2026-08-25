-- 판정 시간 규칙 정합(인증 정책 §2 · 인증 구현 테크스펙 §5-1, 2026-08-25).
--
-- 구 정책은 "잠정 실패 → 방장/공동 관리자 승인·기각" 2단계였다. 신스펙에서 폐기됐다.
--   · 실패는 귀속일 이틀 뒤 00:00 KST 확정 배치가 한 번에 만든다(귀속일 종료 후 하루는 늦은 신호 유예).
--   · 방장·MANAGER·LLM 은 인증을 판정하지 않는다.
--   · 구제는 이의제기 하나뿐이고, 형식 요건을 통과하면 즉시 자동 인용된다.
--
-- 그래서 잠정 실패 상태와 방장 승인 폴백 흔적을 걷어내고, 이의 기한 컬럼의 이름을 실제 의미에 맞춘다.

-- 1) 남아 있는 잠정 실패 행 정리 ------------------------------------------------
--    잠정 실패는 "실패 조건이 확인됐고 이의 창이 열린" 상태였다. 신모델에서 가장 가까운 것은
--    확정된 실패이므로 FAILED 로 옮긴다. 이의 기한·공유 시각은 아래 5) 에서 귀속일 기준으로 다시 세운다.
UPDATE `VerificationDaily`
SET `verifiedAt` = COALESCE(`verifiedAt`, `updatedAt`),
    `status`     = 'FAILED'
WHERE `status` = 'FAILED_PROVISIONAL';

UPDATE `challenge_members`
SET `today_status` = 'FAILED'
WHERE `today_status` = 'FAILED_PROVISIONAL';

-- 2) 상태 enum 에서 잠정 실패 제거 ----------------------------------------------
ALTER TABLE `VerificationDaily`
    MODIFY COLUMN `status` enum('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED')
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING'
        COMMENT '저장 상태. 진행중·실패 예정·검사중은 저장하지 않고 조회 시 계산한다';

ALTER TABLE `challenge_members`
    MODIFY COLUMN `today_status` enum('SUCCESS','PENDING','FAILED','NOT_TARGET','NOT_REQUIRED')
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;

-- 3) 확정 경로에서 방장 승인 폴백 제거 -------------------------------------------
--    MANUAL_FALLBACK(방장 승인 예비 폴백)은 폐기 → 수동 확정(MANUAL)으로 흡수한다.
--    OBJECTION(이의 승인) 은 이름을 APPEAL 로 통일한다.
UPDATE `VerificationDaily` SET `verifiedVia` = 'MANUAL' WHERE `verifiedVia` = 'MANUAL_FALLBACK';

ALTER TABLE `VerificationDaily`
    MODIFY COLUMN `verifiedVia` enum('AUTO','MANUAL','OBJECTION','APPEAL')
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;

UPDATE `VerificationDaily` SET `verifiedVia` = 'APPEAL' WHERE `verifiedVia` = 'OBJECTION';

ALTER TABLE `VerificationDaily`
    MODIFY COLUMN `verifiedVia` enum('AUTO','MANUAL','APPEAL')
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL
        COMMENT '확정 경로. AUTO=신호 판정 / MANUAL=수동 체크 / APPEAL=이의 인용 정정';

-- 4) 이의 기한 컬럼 이름을 의미에 맞춘다 -----------------------------------------
--    disputeClosesAt 은 원래 "예비 폴백 이의 윈도우"였다. 지금은 확정 시각과 같은 귀속일+2일 00:00 KST 이므로
--    appealClosesAt 이 맞다. 인덱스도 폴백 조회용이라 더는 쓰이지 않는다.
ALTER TABLE `VerificationDaily`
    DROP INDEX `idxVerificationDailyFallbackDispute`,
    DROP COLUMN `fallbackApprovalStatus`,
    RENAME COLUMN `disputeClosesAt` TO `appealClosesAt`;

ALTER TABLE `VerificationDaily`
    MODIFY COLUMN `appealClosesAt` datetime(6) DEFAULT NULL
        COMMENT '이의 신청 기한 — 확정 시각과 같은 귀속일+2일 00:00 KST. 확정 전에 받는다(상대 24시간 아님)',
    MODIFY COLUMN `finalizeAfter` datetime(6) DEFAULT NULL
        COMMENT '최종 확정 시각 — 귀속일+2일 00:00 KST. 판정 유형과 무관하게 같다';

-- 5) 확정·이의 경계를 신규칙(귀속일+2일 00:00 KST)으로 정렬 -------------------------
--    두 시각 모두 귀속일만으로 정해지므로 판정 결과와 무관하게 일괄로 다시 세운다.
UPDATE `VerificationDaily`
SET `appealClosesAt` = CONVERT_TZ(DATE_ADD(`targetDate`, INTERVAL 2 DAY), '+09:00', '+00:00'),
    `finalizeAfter`  = CONVERT_TZ(DATE_ADD(`targetDate`, INTERVAL 2 DAY), '+09:00', '+00:00')
WHERE `status` = 'PENDING';

UPDATE `VerificationDaily`
SET `appealClosesAt` = CONVERT_TZ(DATE_ADD(`targetDate`, INTERVAL 2 DAY), '+09:00', '+00:00'),
    `shareableAt`    = COALESCE(`verifiedAt`, `shareableAt`)
WHERE `status` = 'FAILED';
