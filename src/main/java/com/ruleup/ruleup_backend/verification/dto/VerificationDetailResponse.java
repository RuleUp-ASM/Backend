package com.ruleup.ruleup_backend.verification.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** §3.3 챌린지 인증 여부 판단(상세/검증 결과 화면). */
public record VerificationDetailResponse(
        String challengeId,
        String title,
        String status,
        Verification verification
) {
    public record Verification(
            String overallStatus,        // ON_TRACK / AT_RISK / FAILED / COMPLETED
            String scheduleType,
            BigDecimal progressRate,
            int successDays,
            int targetDays,
            int remainingDays,
            ChallengeProgress.Period period,
            Today today,
            List<MethodEval> methods,
            List<DailyLog> dailyLogs
    ) {}

    public record Today(
            boolean isTarget,
            String status,
            String windowClosesAt,
            String verifiedAt,
            String verifiedVia,        // AUTO / MANUAL / MANUAL_FALLBACK (§11.3)
            String disputeClosesAt,    // 예비 폴백 이의 윈도우 종료(잠정성공 중)
            String failureReason,
            Map<String, Object> evidence
    ) {}

    public record MethodEval(
            String method,
            String lastEvaluatedAt,
            boolean supported,
            String polarity,
            Map<String, Object> detail
    ) {}

    public record DailyLog(String date, String status, String method, String verifiedAt) {}
}
