package com.ruleup.ruleup_backend.verification.dto;

import java.math.BigDecimal;

/** §3.4 응답. 제출 즉시 SUCCESS. */
public record ManualVerificationResponse(String targetDate, String status, String method, BigDecimal progressRate) {}
