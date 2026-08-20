-- Refresh Token 일일 보관기간 정리용 인덱스.
--  · 일반 토큰: reuse_detected_at IS NULL + revoked_at/expires_at 경계
--  · 재사용 탐지: reuse_detected_at 경계
-- 한 인덱스로 세 정리 쿼리의 선두 조건과 시간순 LIMIT 스캔을 지원한다.
CREATE INDEX `idx_refresh_tokens_cleanup`
    ON `refresh_tokens` (`reuse_detected_at`, `revoked_at`, `expires_at`, `id`);
