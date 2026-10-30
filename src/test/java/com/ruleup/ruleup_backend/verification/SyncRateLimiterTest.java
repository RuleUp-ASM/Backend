package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.verification.config.VerificationProperties;
import com.ruleup.ruleup_backend.verification.service.SyncRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 실패한 sync 는 간격 슬롯을 쓰지 않는다 — 곧바로 다시 누른 동기화가 429 로 튕기면 안 된다. */
class SyncRateLimiterTest {

    private final VerificationProperties properties = mock(VerificationProperties.class);
    private final SyncRateLimiter limiter;

    SyncRateLimiterTest() {
        when(properties.syncMinIntervalSec()).thenReturn(300);
        limiter = new SyncRateLimiter(properties);
    }

    @Test
    @DisplayName("성공한 sync 뒤 300초 안의 재요청은 429")
    void successHoldsSlot() {
        limiter.check("u1", false);

        assertThatThrownBy(() -> limiter.check("u1", false))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getRetryAfterSeconds()).as("남은 대기 초").isBetween(299, 300));
    }

    @Test
    @DisplayName("돌려준 슬롯은 곧바로 다시 쓸 수 있다")
    void releasedSlotIsReusable() {
        limiter.check("u2", false).release();

        assertThatCode(() -> limiter.check("u2", false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("그 사이 다른 요청이 차지한 슬롯은 늦게 돌려줘도 건드리지 않는다")
    void staleReleaseKeepsNewerSlot() throws InterruptedException {
        SyncRateLimiter.Slot first = limiter.check("u3", false);
        first.release();
        Thread.sleep(2);              // 같은 밀리초면 시각이 같아 「다른 요청」을 구분할 수 없다
        limiter.check("u3", false);   // 다음 요청이 새로 차지
        first.release();              // 앞 요청의 늦은 반환

        assertThatThrownBy(() -> limiter.check("u3", false)).isInstanceOf(BusinessException.class);
    }
}
