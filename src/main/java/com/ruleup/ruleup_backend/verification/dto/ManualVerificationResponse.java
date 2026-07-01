package com.ruleup.ruleup_backend.verification.dto;

import java.math.BigDecimal;

public record ManualVerificationResponse(
        String verificationId,
        String targetDate,
        String status,            // SUCCESS / PENDING_APPROVAL
        String method,            // 제출 방식 에코(PHOTO / SELF_CHECK) — 클라 표시용
        String approvalStatus,    // 폴백이면 PENDING, 정규 수동이면 null
        String verifiedVia,       // MANUAL(정규) / null(폴백, 승인 전)
        String disputeClosesAt,   // 승인 모델에선 항상 null(레거시 이의창 미사용)
        BigDecimal progressRate
) {}
