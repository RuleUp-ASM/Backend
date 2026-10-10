package com.ruleup.ruleup_backend.admin.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 관리자 요청 한 건마다 한 줄 — 누가(이메일), 무엇을(메서드·경로), 결과(상태), 언제(로그 시각), 얼마나.
 *
 * <p>조작별 상세(대상 id·조작 종류·허용/거부)는 {@code admin_audit_logs} 에 서비스가 남긴다. 이 줄은
 * 그 표가 담지 못하는 <b>사람 이메일과 HTTP 결과</b>를 CloudWatch 에 남겨, 둘을 시각·경로로 맞춰 볼 수
 * 있게 한다. 토큰·헤더·쿼리스트링·본문은 싣지 않는다 — 검색어나 메모에 개인정보가 들어갈 수 있다.
 */
@Slf4j
public class AdminRequestAuditFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/actuator/health".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Object actor = request.getAttribute(CloudflareAccessFilter.ACTOR_EMAIL_ATTRIBUTE);
            if (actor != null) {
                log.info("admin_request actor={} method={} path={} status={} tookMs={}",
                        actor, request.getMethod(), request.getRequestURI(), response.getStatus(),
                        (System.nanoTime() - started) / 1_000_000);
            }
        }
    }
}
