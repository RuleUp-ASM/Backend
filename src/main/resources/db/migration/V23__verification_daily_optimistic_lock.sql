-- 확정 결과 덮어쓰기 방지(2026-08-25).
--
-- 확정 배치는 FOR UPDATE SKIP LOCKED 로 행을 선점하지만, 일반 sync 는 잠금 없이 같은 행을 갱신한다.
-- 배치가 실패를 확정하는 사이 이미 행을 읽어 둔 sync 가 뒤늦게 flush 하면 그 확정을 덮어써 되돌린다
-- (lost update). "확정 이후 결과가 자동으로 바뀌는 경우 0건"은 절대 조건이라 DB 가 지키게 한다.
--
-- 기존 행은 0 에서 시작한다 — 값 자체에 의미는 없고 갱신 충돌 감지에만 쓴다.

ALTER TABLE `VerificationDaily`
    ADD COLUMN `version` bigint NOT NULL DEFAULT 0
        COMMENT '낙관적 락 — sync 와 확정 배치가 같은 행을 갱신할 때 덮어쓰기를 막는다'
        AFTER `shareableAt`;
