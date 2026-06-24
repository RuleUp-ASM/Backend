package com.ruleup.ruleup_backend.verification.dto;

import java.math.BigDecimal;

/** §11.6 응답. 정규 수동=즉시 SUCCESS / 폴백=잠정 SUCCESS + disputeClosesAt. */
public record ManualVerificationResponse(
        String targetDate,
        String status,            // SUCCESS (폴백이면 잠정)
        String verifiedVia,       // MANUAL / MANUAL_FALLBACK
        String disputeClosesAt,   // 폴백 이의 윈도우 종료(없으면 null)
        String method,
        BigDecimal progressRate
) {}
