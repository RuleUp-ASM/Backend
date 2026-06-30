package com.ruleup.ruleup_backend.verification.domain;

/**
 * 예비 폴백(asFallback=true) 수동 인증의 방장 승인 상태(§9.2 / API .../approval).
 * 값이 없으면(null) 해당 일자 인증은 폴백 제출이 아니다(정규 수동/자동).
 *  - PENDING  : 제출됨, 방장 결정 대기(진행률 미반영)
 *  - APPROVED : 방장 승인 → SUCCESS(verifiedVia=MANUAL_FALLBACK)
 *  - REJECTED : 방장 거절 → FAILED(failureReason=FALLBACK_REJECTED)
 */
public enum FallbackApprovalStatus { PENDING, APPROVED, REJECTED }
