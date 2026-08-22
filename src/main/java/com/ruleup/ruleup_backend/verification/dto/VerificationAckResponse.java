package com.ruleup.ruleup_backend.verification.dto;

/**
 * POST /api/v1/verifications/{verificationId}/ack 응답.
 *
 * @param acknowledged 항상 true. 이후 today 응답에서 unacknowledgedResult가 사라진다
 */
public record VerificationAckResponse(boolean acknowledged) {}
