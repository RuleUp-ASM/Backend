-- 예비 폴백(asFallback) 수동 인증을 "잠정 SUCCESS + 침묵=동의" 에서
-- "방장 승인(PENDING_APPROVAL)" 모델로 전환한다(API 계약 §9.2 / .../verifications/{id}/approval).
-- 승인 상태 컬럼 추가. 기존 disputeClosesAt 은 레거시로 남기되 신규 모델에선 미사용.
ALTER TABLE VerificationDaily
    ADD COLUMN fallbackApprovalStatus VARCHAR(20) NULL AFTER disputeClosesAt;
