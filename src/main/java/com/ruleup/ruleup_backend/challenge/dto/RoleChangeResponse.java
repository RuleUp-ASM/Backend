package com.ruleup.ruleup_backend.challenge.dto;

/** 역할 변경 응답 (§7-1): 변경 후 역할. */
public record RoleChangeResponse(String userId, String role) {}
