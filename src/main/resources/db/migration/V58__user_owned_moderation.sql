-- Pending source values already live on users; requests are no longer the source of truth.
DROP TABLE moderation_requests;
ALTER TABLE users RENAME COLUMN profile_image_url TO profile_image_key;
ALTER TABLE users DROP COLUMN approved_profile_image_url, DROP COLUMN moderation_checked_at;
CREATE INDEX idx_users_nickname_pending ON users(nickname_status, id);
CREATE INDEX idx_users_image_pending ON users(profile_image_status, id);
