-- =====================================================================
-- 원본 신호에 <b>배제 사유</b>를 새긴다 (인증 백엔드 4-3 「신호 게이트」 · 공통 3절 ①)
--
-- 판정이 raw 를 다시 읽어 전량 재평가하도록 바뀌면서, 게이트 결정이 요청 메모리에만
-- 남아 있으면 안 된다. 지금까지 VPN·무결성 실패는 "이번 요청의 신호"에서 빼는 것으로
-- 끝났는데, 다음 sync 가 같은 날짜를 raw 에서 다시 읽으면 그때 빠졌던 신호가 아무 표시
-- 없이 되살아난다 — 게이트가 사실상 한 번만 작동하는 셈이다.
--
-- 그래서 배제를 <b>행에 새긴다</b>. 원본은 그대로 저장하고(스펙: 판정에 안 쓸 뿐 이상탐지
-- 자료로는 남긴다) 판정 조회만 이 값이 비어 있는 행으로 좁힌다.
--
-- 인덱스를 따로 두지 않는다 — 판정 조회는 이미 (observedDate 파티션 + userId) 로 좁혀진
-- 뒤라, 그 안에서 사유 한 컬럼을 보는 비용은 인덱스를 하나 더 유지하는 비용보다 싸다.
-- =====================================================================

ALTER TABLE `verification_location_signals`
    ADD COLUMN `excludeReason` varchar(20) NULL
        COMMENT '판정 배제 사유 — MOCK / VPN / UNTRUSTED_SOURCE / ACCURACY_LOW / MANUAL_ENTRY. NULL 이면 판정 입력'
        AFTER `signalType`;

ALTER TABLE `verification_device_usage_signals`
    ADD COLUMN `excludeReason` varchar(20) NULL
        COMMENT '판정 배제 사유. NULL 이면 판정 입력'
        AFTER `signalType`;

ALTER TABLE `verification_health_connect_signals`
    ADD COLUMN `excludeReason` varchar(20) NULL
        COMMENT '판정 배제 사유. NULL 이면 판정 입력'
        AFTER `signalType`;
