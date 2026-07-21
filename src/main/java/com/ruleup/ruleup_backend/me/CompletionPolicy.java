package com.ruleup.ruleup_backend.me;

import java.math.BigDecimal;

/**
 * 개인 완주 기준(마이프로필 §8). "완주 = progressRate ≥ 90"을 온도 스펙 완주 커트라인(r ≥ 0.9)과
 * 동일 기준으로 공유해 마이 화면 카운트와 온도 서사의 불일치를 막는다.
 */
public final class CompletionPolicy {

    /** 완주 판정 진행률(%) 하한. */
    public static final BigDecimal COMPLETION_RATE_PERCENT = new BigDecimal("90");

    private CompletionPolicy() {}

    public static boolean isCompleted(BigDecimal progressRate) {
        return progressRate != null && progressRate.compareTo(COMPLETION_RATE_PERCENT) >= 0;
    }
}
