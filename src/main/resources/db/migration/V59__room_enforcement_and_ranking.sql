CREATE TABLE challenge_kicks (
    id BINARY(16) NOT NULL PRIMARY KEY,
    challenge_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    source_event_id BINARY(16) NOT NULL,
    evidence JSON NOT NULL,
    is_permanent BOOLEAN NOT NULL,
    kicked_at DATETIME(6) NOT NULL,
    rejoin_available_at DATETIME(6) NULL,
    CONSTRAINT fk_kick_user FOREIGN KEY (user_id) REFERENCES users(id),
    UNIQUE KEY uq_kick_idempotent(challenge_id,user_id,reason,source_event_id),
    INDEX idx_kick_user(user_id,kicked_at DESC),
    INDEX idx_kick_permanent(challenge_id,user_id,is_permanent)
);
INSERT INTO challenge_kicks(id,challenge_id,user_id,reason,source_event_id,evidence,is_permanent,kicked_at,rejoin_available_at)
SELECT id,challenge_id,user_id,kick_reason,id,JSON_OBJECT('migrated',true),rejoin_banned,COALESCE(left_at,joined_at),rejoin_available_at
FROM challenge_members WHERE kick_reason IN ('CHEAT_DETECTED','CONSECUTIVE_FAILURE','PERMISSION_MISSING');

CREATE TABLE challenge_rejoin_backoffs (
    challenge_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    kick_count INT NOT NULL,
    available_at DATETIME(6) NOT NULL,
    PRIMARY KEY(challenge_id,user_id),
    CONSTRAINT fk_backoff_challenge FOREIGN KEY(challenge_id) REFERENCES challenges(id) ON DELETE CASCADE,
    CONSTRAINT fk_backoff_user FOREIGN KEY(user_id) REFERENCES users(id)
);
INSERT INTO challenge_rejoin_backoffs
SELECT challenge_id,user_id,kick_count,rejoin_available_at FROM challenge_members
WHERE kick_count>0 AND rejoin_available_at IS NOT NULL AND rejoin_banned=FALSE;

CREATE TABLE verification_permission_waits (
    challenge_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    signal_type VARCHAR(30) NOT NULL,
    source_event_id BINARY(16) NOT NULL,
    first_observed_at DATETIME(6) NOT NULL,
    waiting_from_on DATE NOT NULL,
    resolved_at DATETIME(6) NULL,
    dispatched_at DATETIME(6) NULL,
    PRIMARY KEY(challenge_id,user_id,signal_type),
    CONSTRAINT fk_permission_wait_challenge FOREIGN KEY(challenge_id) REFERENCES challenges(id) ON DELETE CASCADE
);

CREATE TABLE verification_batch_completions (
    batch_on DATE NOT NULL PRIMARY KEY,
    materialized_at DATETIME(6) NULL,
    finalized_at DATETIME(6) NULL
);
ALTER TABLE room_job_locks ADD COLUMN last_run_on DATE NULL;
CREATE TABLE challenge_cross_ranking_members (
    challenge_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    success_count INT NOT NULL,
    total_count INT NOT NULL,
    PRIMARY KEY(challenge_id,user_id)
);

-- Derived snapshots use logical references: refresh holds the job lock, membership writes hold the room lock.
-- Avoid an inverse FK lock order; the archive transaction explicitly removes both snapshot tables.
ALTER TABLE challenge_cross_ranking_snapshot DROP FOREIGN KEY fk_cross_ranking_challenge;
