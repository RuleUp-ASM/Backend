package com.ruleup.ruleup_backend.challenge.dto;

/**
 * 공동 관리자 임명/해제 요청 (§7-1).
 *  - PROMOTE : MEMBER → MANAGER (OWNER만)
 *  - DEMOTE  : MANAGER → MEMBER (OWNER, 또는 MANAGER 본인의 내려놓기)
 */
public record RoleChangeRequest(String action) {}
