package com.ruleup.ruleup_backend.verification.web;

import com.ruleup.ruleup_backend.verification.service.VerificationMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * sync 가 <b>최종적으로</b> 5xx 로 끝났는지 센다 — 「인증 데이터 접수 실패」 경보의 원천.
 *
 * <p>컨트롤러에서 예외를 잡아 세지 않는 이유: {@code GlobalExceptionHandler} 가 본문 상한 초과 같은
 * 일부 예외를 413 으로 바꾼다. 예외 종류로 추측하면 그 규칙을 여기 한 번 더 적어야 하므로, 예외 처리가
 * 끝난 뒤의 응답 코드만 본다. 등록은 {@link SyncFailureMetricsConfig} 가 sync 경로에만 한다.
 */
public class SyncFailureMetricsFilter extends OncePerRequestFilter {

    private final VerificationMetrics metrics;

    public SyncFailureMetricsFilter(VerificationMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = response.getStatus() >= 500;
        } finally {
            // 필터 밖으로 예외가 새면 컨테이너가 500 을 내린다 — 그것도 접수 실패다.
            if (failed) metrics.syncFailed();
        }
    }
}
