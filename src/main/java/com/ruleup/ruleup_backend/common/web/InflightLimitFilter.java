package com.ruleup.ruleup_backend.common.web;

import com.ruleup.ruleup_backend.common.error.ClientErrorLog;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.error.ErrorResponse;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 태스크 한 대가 동시에 처리하는 API 요청 수의 상한 — 넘치면 잠깐 기다리고, 그래도 자리가 없으면 503 으로 돌려보낸다.
 *
 * <p>상한이 없으면 급증 때 받을 수 있는 것보다 많이 받아 <b>모두가</b> 느려진다. 부하테스트(2026-10, 500→1,500명 급증)에서
 * 요청 스레드 200개가 커넥션 15개를 기다리며 쌓여 쓰기 API p95 가 50초를 넘었고, ALB 가 60초에 끊어 504 가 났다.
 * 일찍 거절하면 받은 요청은 제 속도로 끝나고, 앱은 Retry-After 를 보고 다시 보낸다.
 *
 * <p>헬스체크는 관리 포트(MANAGEMENT_SERVER_PORT)라 여기를 지나지 않는다. {@code /api/} 만 센다.
 */
public class InflightLimitFilter extends OncePerRequestFilter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Semaphore permits;
    private final long waitMillis;
    private final Counter rejected;

    public InflightLimitFilter(int maxInflight, long waitMillis, MeterRegistry registry) {
        this.permits = new Semaphore(maxInflight, true);
        this.waitMillis = waitMillis;
        this.rejected = Counter.builder("http_inflight_rejected_total")
                .description("동시 처리 상한에 걸려 503 으로 돌려보낸 요청 수").register(registry);
        registry.gauge("http_inflight_available", permits, Semaphore::availablePermits);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            rejected.increment();
            ErrorCode code = ErrorCode.TEMPORARILY_UNAVAILABLE;
            ClientErrorLog.record(request, code);
            response.setStatus(code.getStatus().value());
            response.setHeader("Retry-After", "2");
            response.setContentType("application/json;charset=UTF-8");
            JSON.writeValue(response.getOutputStream(), ApiResponse.fail(ErrorResponse.of(code)));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}
