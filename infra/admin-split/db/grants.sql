-- 생성 파일 — 직접 고치지 말고 admin-grants.txt 를 고친 뒤 render_grants.py 를 돌린다.
-- 여러 번 돌려도 같은 결과다(CREATE USER IF NOT EXISTS + 비밀번호 재설정 + 권한 재부여).

-- 1) 마이그레이션 계정 — 마이그레이션 태스크에만 준다
CREATE USER IF NOT EXISTS 'ruleup_migrator'@'%' IDENTIFIED BY '${MIGRATOR_DB_PASSWORD}';
ALTER USER 'ruleup_migrator'@'%' IDENTIFIED BY '${MIGRATOR_DB_PASSWORD}' __REQUIRE_SSL__;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, CREATE VIEW, SHOW VIEW, TRIGGER, LOCK TABLES, EXECUTE, CREATE ROUTINE, ALTER ROUTINE ON `__SCHEMA__`.* TO 'ruleup_migrator'@'%';

-- 2) 공개 API 계정 — 전 테이블 DML, DDL 은 파티션 정비 테이블에만
CREATE USER IF NOT EXISTS 'ruleup_app'@'%' IDENTIFIED BY '${APP_DB_PASSWORD}';
ALTER USER 'ruleup_app'@'%' IDENTIFIED BY '${APP_DB_PASSWORD}' __REQUIRE_SSL__;
GRANT SELECT, INSERT, UPDATE, DELETE ON `__SCHEMA__`.* TO 'ruleup_app'@'%';
GRANT ALTER, CREATE, DROP ON `__SCHEMA__`.`verification_location_signals` TO 'ruleup_app'@'%';
GRANT ALTER, CREATE, DROP ON `__SCHEMA__`.`anomaly_location_events` TO 'ruleup_app'@'%';
GRANT ALTER, CREATE, DROP ON `__SCHEMA__`.`verification_device_usage_signals` TO 'ruleup_app'@'%';
GRANT ALTER, CREATE, DROP ON `__SCHEMA__`.`anomaly_device_usage_events` TO 'ruleup_app'@'%';
GRANT ALTER, CREATE, DROP ON `__SCHEMA__`.`verification_health_connect_signals` TO 'ruleup_app'@'%';
GRANT ALTER, CREATE, DROP ON `__SCHEMA__`.`anomaly_health_connect_events` TO 'ruleup_app'@'%';

-- 3) 관리자 API 계정 — 실행 근거가 있는 테이블·작업만(admin-grants.txt)
CREATE USER IF NOT EXISTS 'ruleup_admin'@'%' IDENTIFIED BY '${ADMIN_DB_PASSWORD}';
ALTER USER 'ruleup_admin'@'%' IDENTIFIED BY '${ADMIN_DB_PASSWORD}' __REQUIRE_SSL__;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'ruleup_admin'@'%';
GRANT SELECT, INSERT ON `__SCHEMA__`.`admin_audit_logs` TO 'ruleup_admin'@'%';
GRANT SELECT, INSERT, UPDATE ON `__SCHEMA__`.`announcements` TO 'ruleup_admin'@'%';
GRANT SELECT, UPDATE ON `__SCHEMA__`.`anomaly_signals` TO 'ruleup_admin'@'%';
GRANT SELECT, INSERT ON `__SCHEMA__`.`ban_list` TO 'ruleup_admin'@'%';
GRANT SELECT, DELETE ON `__SCHEMA__`.`challenge_cross_ranking_members` TO 'ruleup_admin'@'%';
GRANT SELECT, UPDATE, DELETE ON `__SCHEMA__`.`challenge_cross_ranking_snapshot` TO 'ruleup_admin'@'%';
GRANT SELECT, DELETE ON `__SCHEMA__`.`challenge_delegations` TO 'ruleup_admin'@'%';
GRANT SELECT, INSERT, UPDATE ON `__SCHEMA__`.`challenge_final_ranking` TO 'ruleup_admin'@'%';
GRANT SELECT, INSERT, UPDATE ON `__SCHEMA__`.`challenge_history` TO 'ruleup_admin'@'%';
GRANT SELECT, DELETE ON `__SCHEMA__`.`challenge_invitations` TO 'ruleup_admin'@'%';
GRANT SELECT, DELETE ON `__SCHEMA__`.`challenge_join_events` TO 'ruleup_admin'@'%';
GRANT SELECT, INSERT, UPDATE ON `__SCHEMA__`.`challenge_member_history` TO 'ruleup_admin'@'%';
GRANT SELECT, DELETE ON `__SCHEMA__`.`challenge_members` TO 'ruleup_admin'@'%';
GRANT SELECT, DELETE ON `__SCHEMA__`.`challenge_stats` TO 'ruleup_admin'@'%';
GRANT SELECT, UPDATE, DELETE ON `__SCHEMA__`.`challenges` TO 'ruleup_admin'@'%';
GRANT SELECT ON `__SCHEMA__`.`cycle_score_states` TO 'ruleup_admin'@'%';
GRANT SELECT, INSERT, UPDATE ON `__SCHEMA__`.`daily_service_stats` TO 'ruleup_admin'@'%';
GRANT SELECT, UPDATE ON `__SCHEMA__`.`inquiries` TO 'ruleup_admin'@'%';
GRANT SELECT, INSERT, UPDATE ON `__SCHEMA__`.`notifications` TO 'ruleup_admin'@'%';
GRANT INSERT ON `__SCHEMA__`.`outage_reliefs` TO 'ruleup_admin'@'%';
GRANT SELECT, INSERT ON `__SCHEMA__`.`outbox_messages` TO 'ruleup_admin'@'%';
GRANT SELECT ON `__SCHEMA__`.`report_snapshots` TO 'ruleup_admin'@'%';
GRANT SELECT, UPDATE ON `__SCHEMA__`.`reports` TO 'ruleup_admin'@'%';
GRANT SELECT, UPDATE ON `__SCHEMA__`.`room_job_locks` TO 'ruleup_admin'@'%';
GRANT SELECT, INSERT, UPDATE ON `__SCHEMA__`.`sanctions` TO 'ruleup_admin'@'%';
GRANT SELECT, INSERT ON `__SCHEMA__`.`user_information` TO 'ruleup_admin'@'%';
GRANT SELECT ON `__SCHEMA__`.`user_interests` TO 'ruleup_admin'@'%';
GRANT SELECT, INSERT, UPDATE ON `__SCHEMA__`.`users` TO 'ruleup_admin'@'%';
GRANT SELECT ON `__SCHEMA__`.`verification_appeals` TO 'ruleup_admin'@'%';
GRANT SELECT ON `__SCHEMA__`.`VerificationDaily` TO 'ruleup_admin'@'%';
GRANT SELECT, DELETE ON `__SCHEMA__`.`watcher_invitations` TO 'ruleup_admin'@'%';
GRANT SELECT, DELETE ON `__SCHEMA__`.`watcher_notices` TO 'ruleup_admin'@'%';
GRANT SELECT, DELETE ON `__SCHEMA__`.`watcher_reactions` TO 'ruleup_admin'@'%';
GRANT SELECT, DELETE ON `__SCHEMA__`.`watcher_relations` TO 'ruleup_admin'@'%';

-- 연결 상한 — 한 계정이 RDS 연결을 다 먹지 못하게(풀 크기 × 최대 태스크 수 × 롤링 2배 + 여유)
ALTER USER 'ruleup_admin'@'%' WITH MAX_USER_CONNECTIONS 20;
ALTER USER 'ruleup_migrator'@'%' WITH MAX_USER_CONNECTIONS 5;

SHOW GRANTS FOR 'ruleup_admin'@'%';
