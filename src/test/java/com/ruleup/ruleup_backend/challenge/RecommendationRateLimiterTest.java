package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.challenge.recommendation.RecommendationRateLimiter;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 추천 rate limit(Step0, §3-2): 1분 10회. 11번째부터 429 RECOMMENDATION_RATE_LIMITED + retryAfterSeconds(reason).
 */
class RecommendationRateLimiterTest {

    @Test
    void allowsTenThenBlocksEleventh() {
        RecommendationRateLimiter limiter = new RecommendationRateLimiter();
        String user = "user-1";

        for (int i = 0; i < 10; i++) {
            final int n = i;
            assertThatCode(() -> limiter.check(user)).as("call %d", n).doesNotThrowAnyException();
        }

        BusinessException ex = catchThrowableOfType(() -> limiter.check(user), BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RECOMMENDATION_RATE_LIMITED);
        assertThat(ex.getDetail()).isNotNull();                 // retryAfterSeconds
        assertThat(Integer.parseInt(ex.getDetail())).isBetween(1, 60);
    }

    @Test
    void isolatesPerUser() {
        RecommendationRateLimiter limiter = new RecommendationRateLimiter();
        for (int i = 0; i < 10; i++) limiter.check("a");
        // 다른 사용자는 자기 윈도우라 영향 없음.
        assertThatCode(() -> limiter.check("b")).doesNotThrowAnyException();
    }
}
