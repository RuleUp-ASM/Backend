package com.ruleup.ruleup_backend.watcher.dto;

/** OTP 발송 응답. */
public record OtpSendResponse(String otpId, int expiresInSec, int resendAvailableInSec) {}
