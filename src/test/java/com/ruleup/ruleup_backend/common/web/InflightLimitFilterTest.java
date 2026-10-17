package com.ruleup.ruleup_backend.common.web;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InflightLimitFilterTest {

    @Test
    void overLimitIsRejectedWith503AfterShortWait() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new InflightLimitFilter(1, 50, registry);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try {
                filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me/home"), new MockHttpServletResponse(),
                        (req, res) -> {
                            inside.countDown();
                            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        holder.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        var rejected = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/challenges/explore"), rejected,
                (req, res) -> { throw new AssertionError("자리가 없으면 본문까지 가지 않는다"); });
        assertThat(rejected.getStatus()).isEqualTo(503);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("2");
        assertThat(rejected.getContentAsString()).contains("TEMPORARILY_UNAVAILABLE");
        assertThat(registry.counter("http_inflight_rejected_total").count()).isEqualTo(1.0);

        // 비-API 경로는 세지 않는다
        var other = new MockHttpServletResponse();
        boolean[] passed = {false};
        filter.doFilter(new MockHttpServletRequest("GET", "/health/public"), other, (req, res) -> passed[0] = true);
        assertThat(passed[0]).isTrue();

        release.countDown();
        holder.join(5000);
        var after = new MockHttpServletResponse();
        boolean[] ran = {false};
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me/home"), after, (req, res) -> ran[0] = true);
        assertThat(ran[0]).as("끝난 요청이 자리를 돌려준다").isTrue();
    }
}
