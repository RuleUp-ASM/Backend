-- 확정 기한은 정책 값이고 재시도 시각은 처리 상태다. 서로 덮어쓰지 않는다.
ALTER TABLE VerificationDaily ADD COLUMN finalizeRetryAt DATETIME(6) NULL;

-- 선택한 이상 행을 재판정하기 전 원본을 보존한다. 운영 복구 도구만 기록한다.
CREATE TABLE verification_integrity_repairs (
    verification_id BINARY(16) NOT NULL,
    original_version BIGINT NOT NULL,
    snapshot JSON NOT NULL,
    repaired_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (verification_id, original_version)
);
