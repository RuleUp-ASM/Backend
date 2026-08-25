package com.ruleup.ruleup_backend.verification.dto;

/**
 * DELETE /api/v1/verifications/{verificationId} 응답.
 *
 * @param canceled 항상 true. 해당 일자는 다시 IN_PROGRESS로 돌아간다
 */
public record VerificationCancelResponse(boolean canceled) {}
