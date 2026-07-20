package com.ruleup.ruleup_backend.verification.domain;

/**
 * 이의 제기 처리 상태(§8.7).
 *  - PENDING  : 제출됨, 방장/공동 관리자 처리 대기. 마감 후에도 처리될 때까지 확정 보류(자동 기각 아님).
 *  - APPROVED : 승인 → 해당 일자 SUCCESS 확정(verifiedVia=OBJECTION).
 *  - REJECTED : 기각 → 해당 일자 FAILED 확정(failureReason=OBJECTION_REJECTED).
 */
public enum ObjectionStatus { PENDING, APPROVED, REJECTED }
