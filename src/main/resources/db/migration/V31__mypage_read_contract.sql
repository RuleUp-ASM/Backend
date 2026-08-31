-- 마이페이지 · 프로필 화면 — 테크 스펙 공통(2026-08-31).
--
-- 이 모듈은 조회 전용이라 "소유하는 테이블이 없다"(5-3). 그래서 이 마이그레이션도 새 테이블을
-- 만들지 않고, 화면 조합 패턴이 요구하는 것만 손댄다.
--   ① 점수 변동을 화면에 그릴 수 있게 하는 두 칼럼 (어떤 사건이었나 · 어느 방이었나)
--   ② 닉네임·사진 통합 잠금의 기준 시각
--   ③ 5-3 이 "없으면 p95 1초를 못 맞춘다"고 못박은 인덱스들
--
-- 티어 히스토리는 별도 스냅샷 테이블을 두지 않고 score_transactions.balance_after 에서 파생한다.
-- 스펙이 "소급 정정 시 재계산된 값으로 다시 그린다"고 요구하는데, 스냅샷을 물질화해 두면 이의
-- 인용마다 과거 월을 되짚어 고쳐야 한다. 원장에서 파생하면 정정이 곧 재계산이다.

-- ① 점수 변동 원장 — 화면이 읽을 수 있는 형태로 -----------------------------------
--
-- 기존 transaction_type(CHALLENGE_SUCCESS/FAILURE/APPEAL_ADJUSTMENT/…)은 회계 분류라
-- 화면이 필요로 하는 "사건"과 층이 다르다. 강퇴 −15 와 부정행위 −50 은 회계적으로 둘 다
-- 차감이지만 사용자에게는 전혀 다른 사건이다. 그래서 reason 을 따로 둔다.
--
-- 값은 API 명세(내 티어 상세 조회, 2026-08-26)가 정한 7종이다. KICK_REPORT(신고 강퇴 −30)는
-- 신고→강퇴 경로가 폐지되며 함께 삭제됐으므로 넣지 않는다.
--
-- 둘 다 NULL 허용이다. 지금 이 테이블은 비어 있고(쓰는 코드가 아직 없다), 값을 채우는 것은
-- 점수 산식 스택의 몫이다. 읽는 쪽은 reason 이 없는 행을 최근 변동에서 건너뛴다.
ALTER TABLE `score_transactions`
    ADD COLUMN `reason` ENUM('CYCLE_SUCCESS','CYCLE_FAIL','LEAVE','KICK_FAIL','KICK_PERMISSION',
                             'CHEAT','APPEAL_RESTORE') NULL
        COMMENT '화면에 표시하는 사건 종류. 회계 분류(transaction_type)와 층이 다르다' AFTER `source_id`,
    ADD COLUMN `challenge_id` BINARY(16) NULL
        COMMENT '변동을 일으킨 챌린지. source_id 는 다형적 참조라 방 단위로 못 읽는다' AFTER `reason`;

-- ② 닉네임·사진 통합 1개월 잠금 -----------------------------------------------------
--
-- 스펙은 "둘 중 하나라도 바꾸는 저장을 하면 그 시점부터 두 항목 모두 1개월 잠금"이라고 못박는다.
-- 항목별 변경 시각(nickname_changed_at)으로는 이 규칙을 표현할 수 없다 — 사진만 바꾼 사람의
-- 닉네임 잠금을 판정할 근거가 없기 때문이다. 그래서 "저장 시각"을 한 칸으로 둔다.
--
-- 이 한 칸이 동시 수정 규칙도 함께 해결한다. 사진 등록 직후 10분 안의 닉네임 변경은 같은 저장
-- 세션으로 묶여 잠기지 않아야 하는데, 잠금 시작 시각을 알고 있으면 "방금 시작한 잠금인가"로 판정된다.
ALTER TABLE `users`
    ADD COLUMN `profile_changed_at` DATETIME(3) NULL
        COMMENT '닉네임·사진 통합 잠금 시작 시각. +1개월이 해제일, +10분이 같은 저장 세션 경계';

-- 기존 사용자는 닉네임 변경 시각을 잠금 시작으로 물려받는다. 잠금은 보수적인 쪽이 안전하다.
UPDATE `users` SET `profile_changed_at` = `nickname_changed_at` WHERE `nickname_changed_at` IS NOT NULL;

-- ③ 화면 조합이 요구하는 인덱스 (5-3) -----------------------------------------------
--
-- RoutineOutcome 은 (challengeId, userId, targetDate) UNIQUE 밖에 없어 사용자 선두 조회가
-- 전부 풀스캔이다. 월 캘린더는 30일치를 한 번에 읽고, 일자 상세는 그날 것만 읽는다 —
-- 두 경로가 같은 (userId, targetDate) 접두사를 쓰므로 인덱스 하나가 둘 다 덮는다.
-- challengeId 를 뒤에 붙여 일자 상세가 커버링 인덱스로 끝나게 한다.
CREATE INDEX `ixRoutineOutcomeUserDate`
    ON `RoutineOutcome` (`userId`, `targetDate`, `challengeId`);

-- 통계 4종은 확정된 건만 센다. RoutineOutcome 은 확정분만 쌓이므로 상태만 있으면 된다.
CREATE INDEX `ixRoutineOutcomeUserStatus`
    ON `RoutineOutcome` (`userId`, `status`);

-- 이탈 챌린지 목록은 강퇴·중도 탈퇴를 구분해 보여주므로 left_type 이 선두에 필요하다.
CREATE INDEX `ix_challenge_members_left`
    ON `challenge_members` (`user_id`, `left_type`, `left_at` DESC);
