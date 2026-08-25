package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 한 method의 평가 결과. 종합 판정과 verification_daily/method_result 반영의 입력.
 * WAKE의 firstUnlockAt 등 산출값은 evidence 맵에 담겨 다음 sync의 prior로 재사용된다.
 *
 * <p>평가기는 <b>실패를 확정하지 않는다</b>. 위반·미달을 찾으면 {@link #violated}로 사유만 붙여 돌려주고,
 * 최종 실패는 귀속일 다음 날 00:00 KST 확정 배치가 만든다(인증 정책 §2). 성공만 즉시 확정한다.
 */
public record EvaluationOutcome(
        VerificationStatus status,
        String failureReason,        // 위반·미달 사유 코드(없으면 null)
        Map<String, Object> evidence,// {firstUnlockAt, usageMinutes, distanceKm, ...} (없으면 null)
        Instant windowClosesAt       // 창 닫힘 시각(시간창이 있는 유형, 없으면 null)
) {
    /** 성공 조건 충족 — 즉시 완료 확정 대상. */
    public static EvaluationOutcome success(Map<String, Object> evidence, Instant windowClosesAt) {
        return new EvaluationOutcome(VerificationStatus.SUCCESS, null, evidence, windowClosesAt);
    }

    /**
     * 위반·미달 확인 — "실패 예정". 상태는 <b>미확정(PENDING)</b>으로 두고 사유만 싣는다.
     * 이탈·해제 신호가 늦게 도착해 확정 전에 뒤집힐 수 있으므로 여기서 실패로 굳히지 않는다.
     */
    public static EvaluationOutcome violated(String reason, Map<String, Object> evidence, Instant windowClosesAt) {
        return new EvaluationOutcome(VerificationStatus.PENDING, reason, evidence, windowClosesAt);
    }

    /** 아직 판단할 근거가 없음 — 진행중. */
    public static EvaluationOutcome pending(Map<String, Object> evidence, Instant windowClosesAt) {
        return new EvaluationOutcome(VerificationStatus.PENDING, null, evidence, windowClosesAt);
    }

    /** 위반·미달이 확인됐지만 아직 확정되지 않은 상태인지. */
    public boolean isFailExpected() {
        return status == VerificationStatus.PENDING && failureReason != null;
    }
}
