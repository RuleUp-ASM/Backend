-- 부하테스트(2026-10, DAU 2만 기준) 점검에서 찾은, 배치가 표 전체를 훑는 조회 두 곳.

-- 고스트 푸시 선점(1시간마다): status·setup_status 로 거른 뒤 joined_at 순서로 FOR UPDATE SKIP LOCKED.
-- 지금은 joined_at 인덱스로 멤버 전체를 가입순으로 훑으며 거르는데, REPEATABLE READ 에서는 훑은 행이 모두 잠겨
-- 그동안 sync·가입이 멤버 행을 고치지 못한다. 셋업 미완료 멤버만 가입순으로 읽게 한다.
ALTER TABLE challenge_members
    ADD KEY ix_member_setup_pending (status, setup_status, joined_at),
    ALGORITHM = INPLACE, LOCK = NONE;

-- 인기 점수 배치(5분마다)·일별 지표: 최근 24시간 가입 사건을 시각 범위로 센다. (challenge_id, joined_at) 인덱스로는
-- 시각만으로 범위를 좁힐 수 없어 가입 사건 전체(누적, 계속 는다)를 훑었다.
ALTER TABLE challenge_join_events
    ADD KEY ix_join_events_time (joined_at, challenge_id),
    ALGORITHM = INPLACE, LOCK = NONE;

-- 시작일 도달 활성화·종료일 지난 완료(1분마다): status + 날짜로 FOR UPDATE SKIP LOCKED 선점. 맞는 인덱스가 없어
-- 방 전체(부하테스트 3.5만 행)를 훑었고, REPEATABLE READ 라 훑은 모든 방 행이 그 트랜잭션 동안 잠겨 가입·통계 재계산이 기다렸다.
ALTER TABLE challenges
    ADD KEY ix_challenges_status_start (status, start_date),
    ADD KEY ix_challenges_status_end (status, end_date),
    ALGORITHM = INPLACE, LOCK = NONE;
