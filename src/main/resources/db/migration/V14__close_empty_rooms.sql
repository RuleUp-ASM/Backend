-- ======================================================================
-- 활성 멤버가 0명인데 닫히지 않은 방을 닫는다(삭제 표시)
--
-- 지금까지는 마지막 멤버가 나가도 봇방장으로 넘기기만 해서, 아무도 없는 방이 하루 1번 도는
-- 유령방 삭제 배치가 지울 때까지(정산 대기가 있으면 1주 넘게) 탐색·인기에 남았다. 이제는 나가는
-- 순간 닫는다(ChallengeMemberService#closeIfLastMember). 이미 그렇게 남아 있는 방도 같은 상태로
-- 맞춘다 — 탐색 인덱스는 주기 보정이 빼내고, 이력과 실제 삭제는 같은 배치가 이어서 한다.
-- ======================================================================

UPDATE `challenges` c
   SET c.`deleted_at` = UTC_TIMESTAMP(6)
 WHERE c.`deleted_at` IS NULL
   AND NOT EXISTS (SELECT 1 FROM `challenge_members` m
                    WHERE m.`challenge_id` = c.`id` AND m.`status` = 'ACTIVE');
