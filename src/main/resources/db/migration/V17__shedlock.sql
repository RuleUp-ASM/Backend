-- ======================================================================
-- 배치 분산 락 (ShedLock)
--
-- @Scheduled 는 태스크마다 돈다. 운영에서 태스크를 둘 이상 띄우면 확정·정산·알림 배치가
-- 태스크 수만큼 겹쳐 돌아 푸시가 중복되고 원장 갱신이 경합한다. 배치마다 이 표의 한 행을
-- 잡은 태스크만 실행하고, 나머지는 그 주기를 건너뛴다.
--
-- 시각은 DB 시계로 비교한다(usingDbTime) — 태스크 간 시계가 어긋나도 판정이 갈리지 않는다.
-- 행은 배치가 처음 돌 때 생기므로 시드는 없다.
-- ======================================================================

CREATE TABLE `shedlock` (
  `name`       varchar(64)  NOT NULL COMMENT '배치 이름(클래스.메서드)',
  `lock_until` timestamp(3) NOT NULL COMMENT '이 시각까지 다른 태스크는 실행하지 않는다',
  `locked_at`  timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '락을 잡은 시각',
  `locked_by`  varchar(255) NOT NULL COMMENT '락을 잡은 호스트',
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='배치 분산 락(ShedLock)';
