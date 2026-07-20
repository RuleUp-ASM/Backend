package com.ruleup.ruleup_backend.challenge.dto;

/** 방장 위임 요청 생성 body (§7-2). targetUserId = 위임 대상(MANAGER). */
public record DelegationRequestBody(String targetUserId) {}
