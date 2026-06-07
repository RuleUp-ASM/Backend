package com.ruleup.ruleup_backend.challenge.dto;

/** 3.7 승인/거절 응답. ACTIVE(승인) / REMOVED(거절). */
public record MemberActionResponse(String memberStatus) {}