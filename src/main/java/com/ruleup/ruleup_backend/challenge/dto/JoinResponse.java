package com.ruleup.ruleup_backend.challenge.dto;

/** 3.6 참여 신청 응답. PENDING(승인 대기) / ACTIVE(즉시 참여). */
public record JoinResponse(String memberStatus) {}