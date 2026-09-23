-- ======================================================================
-- 판정 버전을 낙관적 락 버전에서 떼어낸다 (QA TIER-05 · TIER-15)
--
-- 점수 도메인은 「이 판정을 이미 반영했는가」를 VerificationDaily.version 으로 물었다.
-- 그 값은 낙관적 락의 것이라 판정과 무관한 갱신에도 오른다 — 사용자가 결과 모달을
-- 확인하기만 해도 올라간다. 그러면 점수 동기화가 새 판정으로 읽고, 그 시각 이후 원장을
-- 통째로 되감았다가 똑같은 값으로 다시 쌓는다. 화면에는 CYCLE_FAIL -1 과
-- APPEAL_RESTORE +1 이 짝을 지어 늘어난다.
--
-- scoreVersion 은 상태가 바뀔 때만 오른다. 기존 행은 version 을 그대로 물려받는다 —
-- 이미 쌓인 원장의 source_version 이 그 값 기준이라, 여기서 0 으로 두면 전 계정이
-- 미반영으로 보여 한 번에 재처리된다.
-- ======================================================================

ALTER TABLE `VerificationDaily`
  ADD COLUMN `scoreVersion` bigint NOT NULL DEFAULT 0
  COMMENT '판정이 바뀐 횟수 — 점수 반영 기준. version(낙관적 락)과 다르다' AFTER `version`;

UPDATE `VerificationDaily` SET `scoreVersion` = `version`;
