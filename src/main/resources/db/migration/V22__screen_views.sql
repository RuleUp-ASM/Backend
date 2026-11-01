-- ======================================================================
-- 화면 조회 기록 — 회원이 그날 어떤 화면(그 화면을 그리는 API)을 한 번이라도 열었나
--
-- 서비스 이용 지표 biz.screen.viewers(방 홈·피드·랭킹·통계·기록 등을 보는 사람 비율)의 원천이다.
-- 회원 × 화면 × 날짜(KST) 한 행이라 하루에 몇 번을 열어도 한 행이다(INSERT IGNORE). 횟수는 세지 않는다.
-- 35일 지난 행은 매일 지운다(ScreenViewRecorder.purge) — 7일 칸만 보므로 오래 둘 이유가 없다.
-- users 에 FK 를 걸지 않는다: 읽을 때 users 와 조인해 범위(탈퇴·운영자·제외 계정)를 거르고, 쓰기는 요청 경로라 가볍게 둔다.
-- ======================================================================

CREATE TABLE `user_screen_views` (
  `view_date` date NOT NULL COMMENT 'KST 날짜',
  `screen` varchar(20) NOT NULL COMMENT '화면 — home·room_home·feed·room_ranking·cross_ranking·stats·calendar·tier·members',
  `user_id` binary(16) NOT NULL,
  PRIMARY KEY (`view_date`, `screen`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='회원·화면·날짜별 조회 여부 — 서비스 이용 지표';
