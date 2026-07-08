-- 경로: src/main/resources/db/migration/V17__member_ghost_push.sql
-- 셋업 미완료(권한 없음) 멤버에게 보내는 고스트(무음) 푸시의 재발송 쿨다운 기준 컬럼.
--   · setupStatus=PENDING_SETUP 인 ACTIVE 멤버(AUTO 인증)를 배치가 감지해 무음 데이터 푸시로 앱을 깨우고
--     권한/셋업 재요청을 유도한다. ghostPushedAt 으로 재발송 간격(쿨다운)을 둬 스팸을 막는다.
--   · NULL = 아직 보낸 적 없음. 셋업이 READY 되면 대상에서 빠진다.
ALTER TABLE ChallengeMember
    ADD COLUMN ghostPushedAt DATETIME(6) NULL AFTER fallbackUsedCount;
