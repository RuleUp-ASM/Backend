-- Expand only: keep legacy columns until consumers have moved to the new contract.
ALTER TABLE challenges
    ADD COLUMN moderation_pending_since DATETIME(6) NULL,
    ADD COLUMN moderation_enqueued_at DATETIME(6) NULL,
    ADD INDEX idx_challenge_moderation_pending (moderation_enqueued_at, moderation_pending_since, id),
    MODIFY moderation_description ENUM('NONE','EXEMPT','APPROVED','IN_REVIEW','REJECTED') NOT NULL DEFAULT 'EXEMPT';
UPDATE challenges SET moderation_pending_since = updated_at
WHERE moderation_title = 'IN_REVIEW' OR moderation_description = 'IN_REVIEW' OR moderation_image = 'IN_REVIEW';
UPDATE challenges SET moderation_locked_until = NULL;

ALTER TABLE challenge_history
    ADD COLUMN ai_title_snapshot VARCHAR(30) NULL,
    ADD COLUMN description_snapshot VARCHAR(200) NULL,
    ADD COLUMN owner_id_snapshot BINARY(16) NULL,
    ADD COLUMN owner_type_snapshot VARCHAR(10) NULL,
    ADD COLUMN mode VARCHAR(10) NULL,
    ADD COLUMN visibility VARCHAR(10) NULL,
    ADD COLUMN capacity INT NULL,
    ADD COLUMN min_tier VARCHAR(10) NULL,
    ADD COLUMN weekly_count INT NULL,
    ADD COLUMN verification_config JSON NULL,
    ADD COLUMN params JSON NULL,
    ADD COLUMN penalties JSON NULL,
    ADD COLUMN final_member_count INT NULL,
    ADD COLUMN close_reason VARCHAR(10) NULL,
    ADD COLUMN closed_at DATETIME(6) NULL;
ALTER TABLE challenge_member_history
    ADD COLUMN joined_at DATETIME(6) NULL,
    ADD COLUMN leave_reason VARCHAR(20) NULL,
    ADD COLUMN archived_at DATETIME(6) NULL;
UPDATE challenge_final_ranking SET score_snapshot=score_snapshot/100;
ALTER TABLE challenge_final_ranking
    MODIFY rank_no INT NULL,
    MODIFY score_snapshot DECIMAL(6,4) NULL,
    ADD COLUMN success_count INT NULL,
    ADD COLUMN participations INT NULL,
    ADD COLUMN archived_at DATETIME(6) NULL;

-- Historical verification inputs must survive deleting operational membership rows.
ALTER TABLE verification_setting_snapshots DROP FOREIGN KEY fk_setting_snapshots_member;
ALTER TABLE challenge_member_history ADD COLUMN member_id BINARY(16) NULL,
    ADD COLUMN verification_snapshot JSON NULL;
ALTER TABLE challenge_members ADD COLUMN leave_reason VARCHAR(20) NULL;
UPDATE challenge_members SET leave_reason=CASE left_type WHEN 'LEAVE' THEN 'VOLUNTARY' WHEN 'KICK' THEN 'KICKED' END;
ALTER TABLE challenges MODIFY end_date DATE NULL, MODIFY duration_days INT NULL;
ALTER TABLE challenge_history MODIFY end_date DATE NULL;
ALTER TABLE challenges ADD COLUMN origin VARCHAR(10) NULL, ADD COLUMN source_challenge_id BINARY(16) NULL;
ALTER TABLE challenge_history ADD COLUMN template_id BIGINT NULL, ADD COLUMN origin VARCHAR(10) NULL,
    ADD COLUMN source_challenge_id BINARY(16) NULL;
-- owner_id is Phase 1's single authority. Repair the legacy BOT representation before enforcing it.
UPDATE challenges SET owner_id=NULL WHERE owner_type='BOT';
UPDATE challenges SET owner_type='BOT' WHERE owner_id IS NULL;
ALTER TABLE challenges ADD CONSTRAINT ck_challenge_owner_identity CHECK (
    (owner_type='USER' AND owner_id IS NOT NULL) OR (owner_type='BOT' AND owner_id IS NULL));
