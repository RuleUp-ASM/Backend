package com.ruleup.ruleup_backend.auth.review;

import com.github.benmanes.caffeine.cache.Ticker;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Nested;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

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
 *   <li>IP 는 ALB 가 덧붙인 {@code X-Forwarded-For} 의 <b>마지막</b> 값(ALB 에 붙은 주소)이다 — 앞쪽은 클라이언트가 위조한다.
 *       그 주소가 Cloudflare 엣지면 Cloudflare 가 덮어쓴 {@code CF-Connecting-IP} 를 쓴다. 직접 접근의 CF-Connecting-IP 는 믿지 않는다</li>
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
    // ===== 운영 경로: 앱 → Cloudflare → ALB → 태스크 =====

    /**
     * 운영 경로의 요청. ALB 는 자기에게 붙은 주소(=Cloudflare 엣지)를 X-Forwarded-For 끝에 덧붙이고, Cloudflare 는
     * 실제 클라이언트 주소를 {@code CF-Connecting-IP} 로 덮어쓰고 X-Forwarded-For 에도 덧붙인다. 클라이언트가 보낸
     * X-Forwarded-For 는 앞에 그대로 남는다. ALB 는 직접 접근도 받는다(보안 그룹 0.0.0.0/0).
     */
    @Nested
    @DisplayName("Cloudflare 뒤 — 엣지에서 온 요청만 CF-Connecting-IP 를 믿는다")
    class BehindCloudflare {

        private static final String ALB = "10.1.0.15";
        private static final String CF_EDGE = "172.70.9.9";        // 172.64.0.0/13
        private static final String CF_EDGE_V6 = "2606:4700:10::1"; // 2606:4700::/32

        private MockHttpServletRequest viaCloudflare(String clientSentXff, String client, String edge) {
            MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/auth/review-login");
            r.setRemoteAddr(ALB);
            String chain = (clientSentXff == null ? "" : clientSentXff + ", ") + client + ", " + edge;
            r.addHeader("X-Forwarded-For", chain);
            r.addHeader("CF-Connecting-IP", client);
            return r;
        }

        @Test
        @DisplayName("엣지를 거친 요청은 CF-Connecting-IP 가 클라이언트다 — 엣지 주소로 뭉치지 않는다")
        void usesConnectingIpBehindEdge() {
            assertThat(ReviewLoginAttemptLimiter.clientIp(viaCloudflare(null, "211.36.10.20", CF_EDGE)))
                    .isEqualTo("211.36.10.20");
            assertThat(ReviewLoginAttemptLimiter.clientIp(viaCloudflare(null, "2001:db8::7", CF_EDGE_V6)))
                    .isEqualTo("2001:db8::7");
        }

        @Test
        @DisplayName("같은 엣지를 거친 심사위원 둘은 한도를 함께 쓰지 않는다")
        void judgesBehindSameEdgeAreSeparate() {
            ReviewLoginAttemptLimiter limiter = limiter();
            String first = ReviewLoginAttemptLimiter.clientIp(viaCloudflare(null, "211.36.10.20", CF_EDGE));
            failTimes(limiter, first, MAX_FAILURES);

            String second = ReviewLoginAttemptLimiter.clientIp(viaCloudflare(null, "118.235.4.4", CF_EDGE));
            assertThatCode(() -> limiter.check(second)).doesNotThrowAnyException();
            assertThatThrownBy(() -> limiter.check(first)).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("클라이언트가 X-Forwarded-For 앞쪽을 바꿔도 엣지를 거치면 같은 IP 로 센다")
        void spoofedXffBehindEdgeDoesNotBypass() {
            ReviewLoginAttemptLimiter limiter = limiter();
            for (int i = 0; i < MAX_FAILURES; i++) {
                String ip = ReviewLoginAttemptLimiter.clientIp(viaCloudflare("6.6.6." + i, "211.36.10.21", CF_EDGE));
                limiter.check(ip);
                limiter.recordFailure(ip);
            }
            String next = ReviewLoginAttemptLimiter.clientIp(viaCloudflare("7.7.7.7", "211.36.10.21", CF_EDGE));
            assertThatThrownBy(() -> limiter.check(next)).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("ALB 에 직접 붙은 요청의 CF-Connecting-IP 는 믿지 않는다 — ALB 가 본 주소로 센다")
        void forgedConnectingIpOnDirectAccessIsIgnored() {
            ReviewLoginAttemptLimiter limiter = limiter();
            for (int i = 0; i < MAX_FAILURES; i++) {
                MockHttpServletRequest r = new MockHttpServletRequest();
                r.setRemoteAddr(ALB);
                r.addHeader("X-Forwarded-For", "203.0.113.50");          // ALB 가 붙인 직접 접속 주소
                r.addHeader("CF-Connecting-IP", "9.9.9." + i);           // 지어낸 값
                String ip = ReviewLoginAttemptLimiter.clientIp(r);
                assertThat(ip).isEqualTo("203.0.113.50");
                limiter.check(ip);
                limiter.recordFailure(ip);
            }
            assertThatThrownBy(() -> limiter.check("203.0.113.50")).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("엣지를 거쳤는데 CF-Connecting-IP 가 없으면 Cloudflare 가 덧붙인 값(엣지 바로 앞)을 쓴다")
        void fallsBackToValueBeforeEdge() {
            MockHttpServletRequest r = viaCloudflare("6.6.6.6", "211.36.10.22", CF_EDGE);
            r.removeHeader("CF-Connecting-IP");

            assertThat(ReviewLoginAttemptLimiter.clientIp(r)).isEqualTo("211.36.10.22");
        }

        /**
         * 운영은 {@code server.forward-headers-strategy=framework} 라 {@link ForwardedHeaderFilter} 가 먼저 요청을 감싼다 —
         * X-Forwarded-For 를 <b>숨기고</b> 접속 주소를 그 <b>첫 값</b>(클라이언트가 지어낼 수 있는 값)으로 바꾼다.
         * MockMvc 통합 테스트는 이 필터를 거치지 않으므로 여기서 직접 감싸 본다.
         */
        private HttpServletRequest throughForwardedHeaderFilter(MockHttpServletRequest raw) throws Exception {
            HttpServletRequest[] seen = new HttpServletRequest[1];
            new ForwardedHeaderFilter().doFilter(raw, new MockHttpServletResponse(), new MockFilterChain() {
                @Override public void doFilter(ServletRequest req, ServletResponse res) {
                    seen[0] = (HttpServletRequest) req;
                }
            });
            assertThat(seen[0].getHeader("X-Forwarded-For")).as("필터가 헤더를 숨긴다 — 이 테스트의 전제").isNull();
            return seen[0];
        }

        @Test
        @DisplayName("운영 필터(ForwardedHeaderFilter)를 거쳐도 같은 IP 다 — 엣지 경유")
        void sameIpThroughForwardedHeaderFilterViaEdge() throws Exception {
            HttpServletRequest wrapped = throughForwardedHeaderFilter(viaCloudflare("6.6.6.6", "211.36.10.23", CF_EDGE));

            assertThat(ReviewLoginAttemptLimiter.clientIp(wrapped)).isEqualTo("211.36.10.23");
        }

        @Test
        @DisplayName("운영 필터를 거쳐도 직접 접근은 ALB 가 본 주소다 — 앞쪽 위조 값이 아니다")
        void sameIpThroughForwardedHeaderFilterDirect() throws Exception {
            MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/api/v1/auth/review-login");
            raw.setRemoteAddr(ALB);
            raw.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.51");
            raw.addHeader("CF-Connecting-IP", "9.9.9.9");

            assertThat(ReviewLoginAttemptLimiter.clientIp(throughForwardedHeaderFilter(raw))).isEqualTo("203.0.113.51");
        }
    }
}
