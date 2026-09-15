CREATE TABLE user_activity (
    user_id BINARY(16) NOT NULL PRIMARY KEY,
    last_active_on DATE NOT NULL,
    notified_stage VARCHAR(20) NOT NULL DEFAULT 'NONE',
    CONSTRAINT fk_user_activity_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_activity_sweep (notified_stage, last_active_on, user_id)
);
INSERT INTO user_activity(user_id,last_active_on)
SELECT id, DATE(CONVERT_TZ(COALESCE(last_active_at,created_at),'+00:00','+09:00')) FROM users;

CREATE TABLE device_sync_policies (
    id BINARY(16) NOT NULL PRIMARY KEY,
    priority INT NOT NULL UNIQUE,
    match_condition JSON NOT NULL,
    flush_interval_sec INT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_sync_interval CHECK (flush_interval_sec > 0)
);
INSERT INTO device_sync_policies VALUES
 (UNHEX('01950000000070008000000000000001'),100,'{"lowRam":true}',3600,TRUE),
 (UNHEX('01950000000070008000000000000002'),50,'{"platform":"ANDROID","maxSdk":25}',3600,TRUE),
 (UNHEX('01950000000070008000000000000003'),0,'{}',1800,TRUE);
ALTER TABLE users ADD COLUMN ram_mb INT NULL;

CREATE TABLE signup_token_consumptions (
    jti VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    expires_at DATETIME(3) NOT NULL,
    INDEX idx_signup_consumption_expiry(expires_at)
);
