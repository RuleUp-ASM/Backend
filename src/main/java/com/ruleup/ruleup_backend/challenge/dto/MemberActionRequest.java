package com.ruleup.ruleup_backend.challenge.dto;

/** 3.7 승인/거절 요청. action = APPROVE / REJECT. */
public record MemberActionRequest(String action) {}