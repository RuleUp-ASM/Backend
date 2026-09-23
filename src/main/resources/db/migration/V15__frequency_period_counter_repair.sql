-- ======================================================================
-- 빈도형 주기 카운터를 실제 성공 기록에 맞춘다 (QA MAN-13 · VER-03)
--
-- challenge_members.cur_period_completed 는 sync 경로에서만 올라갔다. 수동 방은
-- sync 를 타지 않으므로 이 값이 0 에 머물렀고, 그 값을 보는 /verifications/today 는
-- 주 몫을 다 채운 뒤에도 계속 「할 차례」라고 답했다. 같은 순간 방 상세는 판정 행을
-- 직접 세어 NOT_TARGET 을 냈다 — 두 화면이 갈린 지점이다.
--
-- 적는 쪽은 코드에서 고쳤다(수동 체크 +1, 취소 -1). 여기서는 그동안 새지 않고 쌓인
-- 값을 한 번 맞춘다. 기준은 추정이 아니라 남아 있는 판정 행이다.
--
-- 주기 구간이 아직 없는 멤버(셋업 전)는 셀 근거가 없으므로 건드리지 않는다.
-- ======================================================================

UPDATE `challenge_members` m
   SET m.`cur_period_completed` = (
           SELECT COUNT(*)
             FROM `VerificationDaily` d
            WHERE d.`challengeMemberId` = m.`id`
              AND d.`status` = 'SUCCESS'
              AND d.`targetDate` BETWEEN m.`cur_period_start` AND m.`cur_period_end`)
 WHERE m.`schedule_type` = 'FREQUENCY'
   AND m.`cur_period_start` IS NOT NULL
   AND m.`cur_period_end` IS NOT NULL;
