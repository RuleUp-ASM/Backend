package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 한 method의 평가 결과. 종합 판정(combiner)·verification_daily/method_result 반영의 입력.
 * WAKE의 firstUnlockAt 등 산출값은 evidence 맵에 담겨 다음 sync의 prior로 재사용된다.
 */
public record EvaluationOutcome(
        VerificationStatus status,
        String failureReason,        // 미충족/실패 사유 코드(없으면 null)
        Map<String, Object> evidence,// {firstUnlockAt, usageMinutes, distanceKm, ...} (없으면 null)
        Instant windowClosesAt       // 창 닫힘 시각(제약형·시간창, 없으면 null)
) {
    public static EvaluationOutcome success(Map<String, Object> evidence, Instant windowClosesAt) {
        return new EvaluationOutcome(VerificationStatus.SUCCESS, null, evidence, windowClosesAt);
    }
    public static EvaluationOutcome failed(String reason, Map<String, Object> evidence, Instant windowClosesAt) {
        return new EvaluationOutcome(VerificationStatus.FAILED, reason, evidence, windowClosesAt);
    }
    public static EvaluationOutcome pending(Map<String, Object> evidence, Instant windowClosesAt) {
        return new EvaluationOutcome(VerificationStatus.PENDING, null, evidence, windowClosesAt);
    }
}
