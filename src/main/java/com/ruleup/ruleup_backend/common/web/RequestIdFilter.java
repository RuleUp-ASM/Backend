package com.ruleup.ruleup_backend.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청마다 {@code requestId} 를 MDC 에 넣는다 — 같은 요청의 로그를 CloudWatch Logs 에서 한 번에 찾기 위해서다
 * (노션 「RuleUp 모니터링 설계」 2절). 로그 줄에는 {@code logging.pattern.correlation} 으로 찍히고, 응답
 * 헤더 {@code X-Request-Id} 로도 돌려줘 앱이 오류를 보고할 때 그 값을 붙일 수 있게 한다.
 *
 * <p>앱이 {@code X-Request-Id} 를 보내면 그 값을 쓰되, 로그 주입을 막으려고 짧은 영숫자·하이픈만 받는다.
 * {@code WatcherAudit} 이 이미 이 MDC 키를 읽고 있다.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    public static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming != null && SAFE.matcher(incoming).matches())
                ? incoming : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
