package com.ruleup.ruleup_backend.verification.dto;

/** 이의 제기 처리(§8.7). decision=APPROVE/REJECT, reason 선택(표시/로깅용). */
public record ObjectionDecisionRequest(String decision, String reason) {}
