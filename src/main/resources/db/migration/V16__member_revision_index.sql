-- 부하테스트(2026-10, DAU 2만 기준) 2차: 방마다 「멤버가 마지막으로 움직인 시각」을 구하는 두 쿼리가 방의 멤버 전체를 훑었다.
--  · 탐색 투영의 원천 리비전  MAX(m.updated_at) WHERE m.challenge_id = c.id   (전수 재구성·값 대조: 방 5만 개에서 56만 행, 최대 74초)
--  · 5분 통계 보정 대상 고르기 EXISTS(... m.challenge_id = c.id AND m.updated_at >= ?)
-- (challenge_id, updated_at) 이면 둘 다 방마다 인덱스 끝 한 칸만 본다. updated_at 단독 인덱스는 「그 사이 움직인 멤버」 범위 조회가 계속 쓴다.
ALTER TABLE challenge_members
    ADD KEY ix_member_challenge_updated (challenge_id, updated_at),
    ALGORITHM = INPLACE, LOCK = NONE;
