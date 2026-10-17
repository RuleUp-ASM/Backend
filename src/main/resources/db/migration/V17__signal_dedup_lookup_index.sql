-- 부하테스트(2026-10, DAU 2만 기준) 2차 P0 재측정: sync 가 신호를 저장할 때 「다른 귀속일에 이미 받은 기록인가」를
-- 보관 기간 전체 날짜 범위로 묻는다(observedDate BETWEEN ? AND ? AND userId = ? AND dedupKey IN (...)).
-- 기존 인덱스는 (observedDate, userId, dedupKey) — 앞 칸이 범위라 userId·dedupKey 를 못 쓰고, (userId, occurredAt) 로
-- 그 사용자 신호 전체를 훑었다(느린 쿼리 4,358건/30분). (userId, dedupKey) 면 키마다 한 번 찾는다.
ALTER TABLE verification_health_connect_signals
    ADD KEY idx_health_connect_signal_user_dedup (userId, dedupKey),
    ALGORITHM = INPLACE, LOCK = NONE;
ALTER TABLE verification_location_signals
    ADD KEY idx_location_signal_user_dedup (userId, dedupKey),
    ALGORITHM = INPLACE, LOCK = NONE;
ALTER TABLE verification_device_usage_signals
    ADD KEY idx_device_usage_signal_user_dedup (userId, dedupKey),
    ALGORITHM = INPLACE, LOCK = NONE;
