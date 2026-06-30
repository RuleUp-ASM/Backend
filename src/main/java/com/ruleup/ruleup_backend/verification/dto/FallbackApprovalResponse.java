package com.ruleup.ruleup_backend.verification.dto;

import java.math.BigDecimal;

/**
 * 방장 승인/거절 결과(API: .../verifications/{id}/approval).
 *  - APPROVE → status=SUCCESS, verifiedVia=MANUAL_FALLBACK, failureReason=null, 진행률 갱신
 *  - REJECT  → status=FAILED,  verifiedVia=null, failureReason=FALLBACK_REJECTED, 진행률 현재값
 */
public record FallbackApprovalResponse(
        String verificationId,
        String targetDate,
        String status,          // SUCCESS / FAILED
        String verifiedVia,     // MANUAL_FALLBACK / null
        String failureReason,   // FALLBACK_REJECTED / null
        BigDecimal progressRate
) {}
