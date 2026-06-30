package com.ruleup.ruleup_backend.verification.dto;

/** 방장의 예비 폴백 승인/거절 요청(API: .../verifications/{id}/approval). */
public record FallbackApprovalRequest(
        String decision,    // APPROVE / REJECT
        String reason       // 거절 사유(선택, 표시/로깅용)
) {}
