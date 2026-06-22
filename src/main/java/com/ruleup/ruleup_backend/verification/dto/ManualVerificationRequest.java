package com.ruleup.ruleup_backend.verification.dto;

/** §3.4 수동 인증 제출. method=PHOTO/SELF_CHECK, targetDate 기본 오늘, imageUrl은 PHOTO 필수. */
public record ManualVerificationRequest(String method, String targetDate, String imageUrl) {}
