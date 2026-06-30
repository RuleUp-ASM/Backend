package com.ruleup.ruleup_backend.verification.dto;

import java.math.BigDecimal;

/**
 * 수동 인증 제출 응답(API 계약).
 *  - 정규 수동(asFallback=false): status=SUCCESS, approvalStatus=null, verifiedVia=MANUAL.
 *  - 예비 폴백(asFallback=true): status=PENDING_APPROVAL, approvalStatus=PENDING, verifiedVia=null,
 *    진행률은 승인 전이라 현재값 유지.
 */
public record ManualVerificationResponse(
        String verificationId,    // 제출 식별자(방장 승인 시 참조)
        String targetDate,
        String status,            // SUCCESS / PENDING_APPROVAL
        String approvalStatus,    // 폴백이면 PENDING, 정규 수동이면 null
        String verifiedVia,       // MANUAL(정규, 즉시) / null(폴백, 승인 전)
        BigDecimal progressRate
) {}
