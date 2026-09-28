-- ======================================================================
-- 일별 서비스 지표 (기존 DB + 일별 집계)
--
-- 가입·참여·인증·이의 기록을 하루 한 번 모아 관리자 조회·CSV 로 본다. 한 행이 KST 달력 하루다.
-- 건수만 둔다 —유저 식별자·닉네임 같은 개인정보는 한 칸도 없다. 비율(인증 성공률)도 저장하지
-- 않고 분자·분모를 따로 둔다. 비율만 남기면 여러 날을 합치거나 정의를 바꿔 다시 계산할 수 없다.
--
-- 행은 배치가 UPSERT 로 덮어쓴다(멱등). 인증 판정은 귀속일 D+2 00:00 KST 에 확정되므로, 같은 날짜를
-- 이튿날·사흗날 다시 계산해 확정 판정으로 갈아 끼운다 — judgement_final 이 그 구분이다.
-- 각 지표의 정확한 정의는 DailyServiceStatsService 주석이 원본이다.
-- ======================================================================

CREATE TABLE `daily_service_stats` (
  `stat_date` date NOT NULL COMMENT 'KST 달력 날짜. 이벤트 지표는 이 날 [00:00, 24:00) KST 에 일어난 사건, 인증 지표는 귀속일이 이 날인 판정',
  `signups` int NOT NULL DEFAULT 0 COMMENT '가입 — 이 날 생성된 MEMBER 계정 수(이후 탈퇴해도 뺄셈하지 않는다)',
  `challenges_created` int NOT NULL DEFAULT 0 COMMENT '유저가 개설한 챌린지 수 — 개설자는 방장으로 곧바로 참여한다(가입 사건이 없다)',
  `challenge_joins` int NOT NULL DEFAULT 0 COMMENT '챌린지 가입 사건 수(challenge_join_events). 같은 방 재입장 포함, 방장 개설 제외',
  `rejoins` int NOT NULL DEFAULT 0 COMMENT '그중 같은 방 재입장 — 이 방에 이전 참여 기록이 있던 가입',
  `participants` int NOT NULL DEFAULT 0 COMMENT '이 날 참여를 시작한(가입 또는 개설) 유저 수 — 중복 제거',
  `returning_participants` int NOT NULL DEFAULT 0 COMMENT '그중 이 날 이전에 다른 챌린지 참여 이력이 있던 유저 수(재참여)',
  `verification_targets` int NOT NULL DEFAULT 0 COMMENT '귀속일이 이 날이고 인증이 필요했던 판정 수(대상 아님·불필요 제외)',
  `verification_attempts` int NOT NULL DEFAULT 0 COMMENT '실제 인증 시도 — 유효한 증거가 접수된 판정 수. 판정(멤버×귀속일) 단위라 재전송이 곱해지지 않는다',
  `judged_success` int NOT NULL DEFAULT 0 COMMENT '판정 성공(이의 인용 포함)',
  `judged_success_appeal` int NOT NULL DEFAULT 0 COMMENT '그중 이의 인용으로 정정된 성공',
  `judged_fail` int NOT NULL DEFAULT 0 COMMENT '판정 실패',
  `judged_fail_no_evidence` int NOT NULL DEFAULT 0 COMMENT '그중 판정 불가(권한 없음·신호 없음) 실패',
  `judgement_pending` int NOT NULL DEFAULT 0 COMMENT '계산 시점에 아직 확정되지 않은 판정',
  `judgement_final` tinyint(1) NOT NULL DEFAULT 0 COMMENT '계산 시각이 귀속일 확정 시각(D+2 00:00 KST) 이후였는지 — 0 이면 판정 칸은 잠정값',
  `appeals` int NOT NULL DEFAULT 0 COMMENT '이의 접수 수 — 이 날 접수(=인용)된 이의',
  `computed_at` datetime(3) NOT NULL COMMENT '마지막 계산 시각(UTC 벽시계)',
  PRIMARY KEY (`stat_date`),
  CONSTRAINT `chk_daily_service_stats_judgement_final` CHECK ((`judgement_final` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='일별 서비스 지표 — 건수만, 개인정보 없음';

-- 귀속일 하루치 판정을 세는 입력. 기존 인덱스는 전부 멤버·유저·챌린지가 앞이라 날짜만으로는
-- 표 전체를 훑는다. 온라인 DDL(INPLACE)로 붙는다.
ALTER TABLE `VerificationDaily`
  ADD INDEX `idx_verification_daily_target_date` (`targetDate`, `status`);
