package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;

/**
 * 인증 방식별 평가기 (Strategy, §1 평가 엔진). Spring이 모든 구현을 List로 주입 → method()로 라우팅.
 * 신호 기반 서버 판정: config 기준으로 이번 sync 신호를 평가해 그 method의 오늘 상태를 낸다.
 */
public interface MethodEvaluator {
    VerificationMethod method();
    EvaluationOutcome evaluate(DayContext ctx);
}
