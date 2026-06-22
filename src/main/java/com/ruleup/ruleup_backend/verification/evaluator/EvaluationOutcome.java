package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.VerificationStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 한 method의 평가 결과. 종합 판정(combiner)·verification_daily/method_result 반영의 입력.
 *  - anchorOnly: base가 daily 판정이 아니라 창 시작 시각(firstUnlockAt)만 제공하는 용도(§2.11).
 */
public record EvaluationOutcome(
        VerificationStatus status,
        String failureReason,        // 미충족/실패 사유 코드(없으면 null)
        Map<String, Object> evidence,// {firstUnlockAt, usageMinutes, distanceKm, ...} (없으면 null)
        Instant windowClosesAt,      // 창 닫힘 시각(제약형·시간창, 없으면 null)
        Instant firstUnlockAt,       // WAKE 앵커 산출값(없으면 null)
        boolean anchorOnly
) {
    public static EvaluationOutcome success(Map<String, Object> evidence, Instant windowClosesAt) {
        return new EvaluationOutcome(VerificationStatus.SUCCESS, null, evidence, windowClosesAt, null, false);
    }
    public static EvaluationOutcome failed(String reason, Map<String, Object> evidence, Instant windowClosesAt) {
        return new EvaluationOutcome(VerificationStatus.FAILED, reason, evidence, windowClosesAt, null, false);
    }
    public static EvaluationOutcome pending(Map<String, Object> evidence, Instant windowClosesAt) {
        return new EvaluationOutcome(VerificationStatus.PENDING, null, evidence, windowClosesAt, null, false);
    }
    public EvaluationOutcome withFirstUnlockAt(Instant t) {
        return new EvaluationOutcome(status, failureReason, evidence, windowClosesAt, t, anchorOnly);
    }
    public EvaluationOutcome asAnchorOnly() {
        return new EvaluationOutcome(status, failureReason, evidence, windowClosesAt, firstUnlockAt, true);
    }
}
