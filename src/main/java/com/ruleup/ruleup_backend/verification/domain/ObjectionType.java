package com.ruleup.ruleup_backend.verification.domain;

/**
 * 이의 제기 유형(§8.7). MVP는 FAILURE(실패 판정 이의)만 지원.
 * ABUSE_PENALTY는 Phase 2 어뷰징 처벌 도입 시를 위한 예약값 — 전송 시 400(UNSUPPORTED_OBJECTION_TYPE).
 */
public enum ObjectionType { FAILURE }
