-- 인증 구현 API 명세 정합(2026-08-22).
--  1) 앵커·대상 앱 변경 한도를 "월 1회"로 바꾼다. 기존 쿨다운(7일/1일)은 마지막 '저장' 시각만 봤는데,
--     첫 설정은 한도를 소진하지 않아야 하므로 '변경(PUT)' 시각을 따로 기록한다.
--  2) 판정 결과 모달 확인(ack)을 저장한다. NULL이면 today 응답의 unacknowledgedResult로 내려간다.

ALTER TABLE `challenge_members`
    ADD COLUMN `anchor_changed_at` datetime(6) NULL
        COMMENT '앵커 변경(PUT my-location) 시각 — 월 1회 한도 기준. 최초 셋업(POST setup)은 소진하지 않으므로 기록하지 않는다'
        AFTER `anchor_updated_at`,
    ADD COLUMN `screen_apps_changed_at` datetime(6) NULL
        COMMENT '대상 앱 변경(PUT my-screen-apps) 시각 — 월 1회 한도 기준. 최초 셋업은 소진하지 않는다'
        AFTER `screen_apps_updated_at`;

ALTER TABLE `VerificationDaily`
    ADD COLUMN `acknowledgedAt` datetime(6) NULL
        COMMENT '판정 결과 모달 확인(POST /verifications/{id}/ack) 시각. NULL이면 today 응답에 unacknowledgedResult로 내려간다'
        AFTER `verifiedAt`;
