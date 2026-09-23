package com.ruleup.ruleup_backend.challenge.dto;

/** 위임 요청 생성 응답 (§7-2). */
public record DelegationResponse(String delegationId, String status, String expiresAt) {}
