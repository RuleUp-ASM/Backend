-- Refuse destructive cutover if old, non-replayable scoring data exists.
-- See docs/score/spec-alignment.md for the verification/export procedure.
CREATE TEMPORARY TABLE score_cutover_preflight (ready INT NOT NULL CHECK (ready = 1));
INSERT INTO score_cutover_preflight SELECT IF(
    (SELECT COUNT(*) FROM score_transactions)=0 AND
    (SELECT COUNT(*) FROM cycle_score_states)=0 AND
    (SELECT COUNT(*) FROM challenge_streaks)=0 AND
    (SELECT COUNT(*) FROM score_corrections)=0 AND
    (SELECT COUNT(*) FROM user_score_summaries WHERE total_score<>10 OR actual_tier<>'BRONZE' OR display_tier<>'BRONZE')=0, 1, 0);
DROP TEMPORARY TABLE score_cutover_preflight;
DROP TABLE score_corrections;
DROP TABLE challenge_streaks;
DROP TABLE score_transactions;
DROP TABLE cycle_score_states;
ALTER TABLE user_score_summaries DROP COLUMN tier_grace_until, DROP COLUMN created_at,
    MODIFY total_score INT NOT NULL DEFAULT 10,
    MODIFY actual_tier VARCHAR(10) NOT NULL DEFAULT 'BRONZE',
    MODIFY display_tier VARCHAR(10) NOT NULL DEFAULT 'BRONZE',
    MODIFY version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_score_range CHECK (total_score BETWEEN 0 AND 2000);
ALTER TABLE challenge_history ADD COLUMN repeat_days JSON NULL;
CREATE TABLE cycle_score_states (
    user_id BINARY(16) NOT NULL,
    challenge_id BINARY(16) NOT NULL,
    cycle_id BINARY(16) NOT NULL,
    cycle_start_on DATE NOT NULL,
    cycle_end_on DATE NOT NULL,
    membership_joined_at_snapshot DATETIME(3) NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    tier_snapshot VARCHAR(10) NOT NULL,
    target_count TINYINT UNSIGNED NOT NULL,
    success_weight SMALLINT UNSIGNED NOT NULL,
    miss_weight SMALLINT UNSIGNED NOT NULL,
    success_count TINYINT UNSIGNED NOT NULL DEFAULT 0,
    miss_count TINYINT UNSIGNED NOT NULL DEFAULT 0,
    raw_cumulative INT NOT NULL DEFAULT 0,
    limited_cumulative INT NOT NULL DEFAULT 0,
    cycle_result VARCHAR(10) NULL,
    success_streak_after INT UNSIGNED NULL,
    failure_streak_after INT UNSIGNED NULL,
    closed_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, challenge_id, cycle_id),
    UNIQUE KEY uq_cycle_period (user_id, challenge_id, cycle_start_on),
    KEY idx_cycle_open (closed_at, challenge_id, cycle_id),
    CONSTRAINT ck_cycle_target CHECK (target_count BETWEEN 1 AND 7),
    CONSTRAINT ck_cycle_counts CHECK (
        success_count <= target_count AND miss_count <= target_count
        AND success_count + miss_count <= target_count
    ),
    CONSTRAINT ck_cycle_limit CHECK (limited_cumulative BETWEEN -20 AND 20),
    CONSTRAINT ck_cycle_close_state CHECK (
        (closed_at IS NULL AND cycle_result IS NULL
         AND success_streak_after IS NULL AND failure_streak_after IS NULL)
        OR
        (closed_at IS NOT NULL AND cycle_result IS NOT NULL
         AND cycle_result IN ('SUCCESS', 'PARTIAL', 'FAILURE', 'INVALID')
         AND success_streak_after IS NOT NULL AND failure_streak_after IS NOT NULL)
    ),
    CONSTRAINT fk_cycle_score_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE score_transactions (
    id BINARY(16) NOT NULL PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    entry_kind VARCHAR(10) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_event_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_version INT UNSIGNED NULL,
    processing_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_at DATETIME(3) NOT NULL,
    effective_order VARBINARY(128) NOT NULL,
    policy_version VARCHAR(32) NULL,
    auth_type VARCHAR(10) NULL,
    challenge_id BINARY(16) NULL,
    cycle_id BINARY(16) NULL,
    incident_type VARCHAR(32) NULL,
    raw_delta INT NOT NULL,
    limited_delta INT NOT NULL,
    applied_delta INT NOT NULL,
    balance_after INT NOT NULL,
    actual_tier_after VARCHAR(10) NOT NULL,
    display_tier_after VARCHAR(10) NOT NULL,
    reversal_of BINARY(16) NULL,
    replacement_of BINARY(16) NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uq_score_idempotency (idempotency_key),
    UNIQUE KEY uq_score_reversal (reversal_of),
    UNIQUE KEY uq_score_replacement (replacement_of),
    KEY idx_score_user_history (user_id, created_at DESC, id DESC),
    KEY idx_score_replay (user_id, effective_at, effective_order),
    KEY idx_score_source (user_id, source_event_key, source_version),
    KEY idx_score_processing (user_id, processing_key, id),
    KEY idx_score_cycle (user_id, challenge_id, cycle_id),
    CONSTRAINT ck_score_entry_kind CHECK (entry_kind IN ('RESULT', 'REVERSAL', 'COMMIT')),
    CONSTRAINT ck_score_balance CHECK (balance_after BETWEEN 0 AND 2000),
    CONSTRAINT ck_score_commit_zero CHECK (
        entry_kind <> 'COMMIT' OR (raw_delta = 0 AND limited_delta = 0 AND applied_delta = 0)
    ),
    CONSTRAINT ck_score_reversal_link CHECK (
        (entry_kind = 'REVERSAL' AND reversal_of IS NOT NULL AND replacement_of IS NULL)
        OR (entry_kind <> 'REVERSAL' AND reversal_of IS NULL)
    ),
    CONSTRAINT ck_score_replacement_kind CHECK (
        replacement_of IS NULL OR entry_kind = 'RESULT'
    ),
    CONSTRAINT ck_score_incident_cycle CHECK (incident_type IS NULL OR cycle_id IS NULL),
    CONSTRAINT fk_score_transaction_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;
