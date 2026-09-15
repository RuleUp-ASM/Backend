-- Existing rows have no evidence of which consent wording was accepted.
-- Preserve their timestamps, but require in-app acceptance of a versioned invitation.
ALTER TABLE watcher_relations ADD COLUMN consent_version VARCHAR(20) NULL;
UPDATE watcher_relations SET status = 'PENDING' WHERE status = 'ACTIVE';

-- Preserve previous opt-outs when removing the relation-specific setting.
INSERT INTO user_notification_settings (user_id, group_challenge, updated_at)
SELECT DISTINCT watcher_user_id, 0, UTC_TIMESTAMP(3) FROM watcher_relations WHERE push_enabled = 0
ON DUPLICATE KEY UPDATE group_challenge = 0, updated_at = UTC_TIMESTAMP(3);
ALTER TABLE watcher_relations DROP CHECK chk_watcher_relation_push,
    DROP INDEX ix_watcher_relation_dispatch, DROP COLUMN push_enabled,
    ADD INDEX ix_watcher_relation_dispatch (challenge_id, target_user_id, status),
    ADD CONSTRAINT chk_watcher_consent CHECK
        (status IN ('PENDING', 'ACTIVE') AND (status <> 'ACTIVE' OR (accepted_at IS NOT NULL AND consent_version IS NOT NULL AND consent_version <> '')));
DROP TABLE watcher_consent_logs;

ALTER TABLE watcher_invitations ADD COLUMN token_hash_binary BINARY(32) NULL;
UPDATE watcher_invitations SET token_hash_binary = UNHEX(token_hash);
ALTER TABLE watcher_invitations DROP INDEX uq_watcher_invitation_token, DROP COLUMN token_hash;
ALTER TABLE watcher_invitations CHANGE token_hash_binary token_hash BINARY(32) NOT NULL,
    ADD UNIQUE KEY uq_watcher_invitation_token (token_hash);
