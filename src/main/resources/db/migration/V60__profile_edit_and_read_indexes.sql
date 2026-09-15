ALTER TABLE users
    ADD COLUMN profile_image_registered_at DATETIME(6) NULL,
    ADD COLUMN profile_save_kind VARCHAR(10) NULL;
UPDATE users SET profile_image_registered_at=created_at WHERE profile_image_key IS NOT NULL;

CREATE INDEX idx_verification_user_date_challenge ON VerificationDaily(userId,targetDate,challengeId);
CREATE INDEX idx_verification_user_status ON VerificationDaily(userId,status);
CREATE INDEX idx_member_user_leave ON challenge_members(user_id,leave_reason,left_at DESC);
