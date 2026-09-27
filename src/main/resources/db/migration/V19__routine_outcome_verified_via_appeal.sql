-- RoutineOutcome.verifiedVia 에 APPEAL 을 더한다.
-- 원천인 VerificationDaily.verifiedVia(V5)는 AUTO/MANUAL/APPEAL 인데 이 표만 옛 목록
-- (AUTO/MANUAL/MANUAL_FALLBACK)으로 남아, 이의 인용으로 정정된 날을 수집하면
-- "Data truncated for column 'verifiedVia'" 로 03:30 수집 배치 전체가 롤백됐다.
-- 워터마크가 멈춰 그 뒤 수집이 전부 막힌다.
-- MANUAL_FALLBACK 은 폐기된 값이지만 남은 행이 있으면 ALTER 가 실패하므로 지우지 않는다.
ALTER TABLE `RoutineOutcome`
  MODIFY `verifiedVia` enum('AUTO','MANUAL','MANUAL_FALLBACK','APPEAL')
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
