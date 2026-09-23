package com.ruleup.ruleup_backend.challenge.dto;

/** 위임 요청 처리 응답 (§7-2). newOwnerUserId 는 ACCEPTED 일 때만 채워진다. */
public record DelegationActionResponse(String status, String newOwnerUserId) {}
