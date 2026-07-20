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
            String status,             // SUCCESS / PENDING / FAILED_PROVISIONAL / FAILED / NOT_TARGET / NOT_REQUIRED
            String windowClosesAt,
            String verifiedAt,
            String verifiedVia,        // AUTO / MANUAL / MANUAL_FALLBACK / OBJECTION
            String disputeClosesAt,    // 이의 제기 창 마감(잠정 실패 중)
            String failureReason,
            Map<String, Object> evidence,
            Objection objection        // FAILED_PROVISIONAL 일 때만. 솔로는 항상 null
    ) {}

    /** 이의 제기 가능 여부·마감·기제출 ID(§8.7). status=FAILED_PROVISIONAL 일 때만 채워진다. */
    public record Objection(boolean available, String deadline, String objectionId) {}

    public record MethodEval(
            String method,
            String lastEvaluatedAt,
            boolean supported,
            String polarity,
            Map<String, Object> detail
    ) {}

    // §11.3: verifiedVia = AUTO / MANUAL / MANUAL_FALLBACK (이 날의 인증 경로).
    public record DailyLog(String date, String status, String method, String verifiedVia, String verifiedAt) {}
}
