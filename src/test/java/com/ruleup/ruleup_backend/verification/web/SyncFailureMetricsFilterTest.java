package com.ruleup.ruleup_backend.verification.web;

import com.ruleup.ruleup_backend.verification.service.VerificationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncFailureMetricsFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SyncFailureMetricsFilter filter = new SyncFailureMetricsFilter(new VerificationMetrics(registry));

    @Test
    @DisplayName("5xx 와 새어 나간 예외만 접수 실패로 세고, 2xx·4xx 반려는 세지 않는다")
    void countsOnlyServerFailures() throws Exception {
        respond(200);
        respond(400);
        respond(413);
        respond(500);
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new MockFilterChain(new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest req, HttpServletResponse res) {
                        throw new IllegalStateException("boom");
                    }
                }))).hasMessage("boom");

        assertThat(registry.get("verification.sync.failed").counter().count()).isEqualTo(2.0);
    }

    private void respond(int status) throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new MockFilterChain(new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest req, HttpServletResponse res) {
                        res.setStatus(status);
                    }
                }));
    }
}
