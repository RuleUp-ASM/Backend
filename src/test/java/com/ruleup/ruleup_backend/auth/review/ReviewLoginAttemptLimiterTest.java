package com.ruleup.ruleup_backend.auth.review;

import com.github.benmanes.caffeine.cache.Ticker;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 코드 로그인 시도 제한 (심사 계정 테크 스펙 5-5 유형 2 · 5-7).
 *
 * <ul>
 *   <li>IP 당 10분에 <b>실패</b> 10회 — 한도를 넘으면 코드가 맞아도 429 {@code TOO_MANY_ATTEMPTS}</li>
 *   <li>성공은 세지 않는다 — 시퀀스 다이어그램에서 횟수가 느는 곳은 불일치 분기뿐이다</li>
 *   <li>IP 는 ALB 가 덧붙인 {@code X-Forwarded-For} 의 <b>마지막</b> 값이다 — 앞쪽은 클라이언트가 위조한다</li>
 *   <li>429 는 다시 시도할 수 있을 때까지의 초를 싣는다 — 앱이 그만큼 확인 버튼을 막는다</li>
 * </ul>
 */
class ReviewLoginAttemptLimiterTest {

    private static final int MAX_FAILURES = 10;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    /** Caffeine 이 읽는 나노초 시계. 테스트가 직접 민다. */
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    private ReviewLoginAttemptLimiter limiter() {
        return new ReviewLoginAttemptLimiter(MAX_FAILURES, WINDOW, ticker);
    }

    private void advance(Duration by) {
        nanos.addAndGet(by.toNanos());
    }

    private static void failTimes(ReviewLoginAttemptLimiter limiter, String ip, int times) {
        for (int i = 0; i < times; i++) {
            limiter.check(ip);
            limiter.recordFailure(ip);
        }
    }

    @Test
    @DisplayName("실패 10회까지는 시도할 수 있고, 11번째 시도는 429 TOO_MANY_ATTEMPTS")
    void blocksAfterTenFailures() {
        ReviewLoginAttemptLimiter limiter = limiter();

        failTimes(limiter, "203.0.113.1", MAX_FAILURES);

        assertThatThrownBy(() -> limiter.check("203.0.113.1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS);
                    assertThat(be.getRetryAfterSeconds())
                            .as("남은 대기 초 — 창 길이를 넘지 않는다")
                            .isNotNull().isPositive().isLessThanOrEqualTo((int) WINDOW.toSeconds());
                });
        assertThat(ErrorCode.TOO_MANY_ATTEMPTS.getStatus().value()).isEqualTo(429);
    }

    @Test
    @DisplayName("성공은 세지 않는다 — 심사위원이 여러 번 로그인해도 막히지 않는다")
    void successesAreNotCounted() {
        ReviewLoginAttemptLimiter limiter = limiter();

        for (int i = 0; i < MAX_FAILURES * 3; i++) {
            assertThatCode(() -> limiter.check("203.0.113.2")).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("IP 가 다르면 서로의 한도를 잡아먹지 않는다")
    void countsPerIp() {
        ReviewLoginAttemptLimiter limiter = limiter();

        failTimes(limiter, "203.0.113.3", MAX_FAILURES);

        assertThatCode(() -> limiter.check("203.0.113.4")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("203.0.113.3")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("창(10분)이 지나면 다시 시도할 수 있다")
    void windowExpires() {
        ReviewLoginAttemptLimiter limiter = limiter();
        failTimes(limiter, "203.0.113.5", MAX_FAILURES);

        advance(WINDOW.plusSeconds(1));

        assertThatCode(() -> limiter.check("203.0.113.5")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("창이 흐르는 동안 남은 대기 초가 줄어든다")
    void retryAfterShrinksOverTime() {
        ReviewLoginAttemptLimiter limiter = limiter();
        failTimes(limiter, "203.0.113.6", MAX_FAILURES);

        int first = retryAfterOf(limiter, "203.0.113.6");
        advance(Duration.ofMinutes(4));
        int later = retryAfterOf(limiter, "203.0.113.6");

        assertThat(later).isLessThan(first);
    }

    private static int retryAfterOf(ReviewLoginAttemptLimiter limiter, String ip) {
        try {
            limiter.check(ip);
        } catch (BusinessException e) {
            return e.getRetryAfterSeconds();
        }
        throw new AssertionError("한도를 넘었는데 통과했다");
    }

    // ===== 클라이언트 IP =====

    @Test
    @DisplayName("X-Forwarded-For 의 마지막 값을 쓴다 — ALB 가 붙인 값이고, 앞쪽은 클라이언트가 지어낼 수 있다")
    void usesLastForwardedValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");   // ALB
        request.addHeader("X-Forwarded-For", "1.2.3.4, 198.51.100.7");

        assertThat(ReviewLoginAttemptLimiter.clientIp(request)).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("위조한 앞쪽 값을 매번 바꿔도 한도를 피하지 못한다")
    void spoofedLeadingValuesDoNotBypass() {
        ReviewLoginAttemptLimiter limiter = limiter();
        for (int i = 0; i < MAX_FAILURES; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Forwarded-For", "6.6.6." + i + ", 198.51.100.8");
            String ip = ReviewLoginAttemptLimiter.clientIp(request);
            limiter.check(ip);
            limiter.recordFailure(ip);
        }

        MockHttpServletRequest next = new MockHttpServletRequest();
        next.addHeader("X-Forwarded-For", "7.7.7.7, 198.51.100.8");
        assertThatThrownBy(() -> limiter.check(ReviewLoginAttemptLimiter.clientIp(next)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("값이 하나뿐이면 그 값을 쓴다")
    void singleForwardedValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", " 198.51.100.9 ");

        assertThat(ReviewLoginAttemptLimiter.clientIp(request)).isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("헤더가 없으면 접속 주소를 쓴다 — 로컬·테스트")
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertThat(ReviewLoginAttemptLimiter.clientIp(request)).isEqualTo("127.0.0.1");
    }
}
