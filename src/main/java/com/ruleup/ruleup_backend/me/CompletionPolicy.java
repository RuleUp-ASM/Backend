package com.ruleup.ruleup_backend.me;

import java.math.BigDecimal;

/**
 * 개인 완주 기준. <b>완주 = 기간 중 80% 이상 성공</b> — 챌린지 탐색 정책의 완주율 커트라인과 같은 값이다.
 *
 * <p>구값은 90% 였다(v1 마이프로필 스펙 {@code progressRate ≥ 90}). 같은 사용자의 같은 방이
 * 탐색 화면에서는 완주로, 마이페이지에서는 미완주로 보이는 불일치가 있어 80% 로 통일했다 —
 * 마이페이지 테크 스펙 오픈 이슈 "완주 기준 불일치 90 vs 80" 해소.
 */
public final class CompletionPolicy {

    /** 완주 판정 진행률(%) 하한. */
    public static final BigDecimal COMPLETION_RATE_PERCENT = new BigDecimal("80");

    private CompletionPolicy() {}

    public static boolean isCompleted(BigDecimal progressRate) {
        return progressRate != null && progressRate.compareTo(COMPLETION_RATE_PERCENT) >= 0;
    }
}
