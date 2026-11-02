package com.ruleup.ruleup_backend.auth.review;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 코드 로그인 시도 제한 — IP 당 창(기본 10분)에 <b>실패</b> N회(기본 10) (심사 계정 테크 스펙 5-5 유형 2).
 *
 * <ul>
 *   <li>성공은 세지 않는다. 한도를 넘으면 코드가 맞아도 429 {@code TOO_MANY_ATTEMPTS} + 남은 초</li>
 *   <li>창은 그 IP 의 첫 실패부터 고정이다 — 실패를 이어가도 늘어나지 않는다</li>
 *   <li>태스크마다 따로 센다(인메모리). 코드가 12자리라 태스크 수만큼 한도가 늘어도 추측은 여전히 불가능하다</li>
 * </ul>
 */
@Component
public class ReviewLoginAttemptLimiter {

    private final int maxFailures;
    private final long windowNanos;
    private final Ticker ticker;
    private final Cache<String, Window> windows;

    @Autowired
    public ReviewLoginAttemptLimiter(
            @Value("${app.review-login.max-failures:10}") int maxFailures,
            @Value("${app.review-login.failure-window:PT10M}") Duration window) {
        this(maxFailures, window, Ticker.systemTicker());
    }

    public ReviewLoginAttemptLimiter(int maxFailures, Duration window, Ticker ticker) {
        this.maxFailures = maxFailures;
        this.ticker = ticker;
        long windowNanos = window.toNanos();
        this.windows = Caffeine.newBuilder()
                .ticker(ticker)
                .maximumSize(100_000)
                .expireAfter(new Expiry<String, Window>() {
                    @Override public long expireAfterCreate(String key, Window value, long currentTime) {
                        return windowNanos;
                    }
                    @Override public long expireAfterUpdate(String key, Window value, long currentTime,
                                                            long currentDuration) {
                        return currentDuration;   // 실패가 이어져도 창은 첫 실패부터 고정
                    }
                    @Override public long expireAfterRead(String key, Window value, long currentTime,
                                                          long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
        this.windowNanos = windowNanos;
    }

    /** 이 IP 가 한도를 넘었으면 429. 시도 전에 부른다. */
    public void check(String ip) {
        Window w = windows.getIfPresent(key(ip));
        if (w == null || w.failures.get() < maxFailures) return;
        long remainingNanos = windowNanos - (ticker.read() - w.startNanos);
        long seconds = Math.max(1, (remainingNanos + 999_999_999L) / 1_000_000_000L);
        throw BusinessException.rateLimited(ErrorCode.TOO_MANY_ATTEMPTS, seconds);
    }

    /** 코드 불일치 한 번을 센다. */
    public void recordFailure(String ip) {
        windows.asMap().compute(key(ip), (k, old) -> {
            Window w = (old != null) ? old : new Window(ticker.read());
            w.failures.incrementAndGet();
            return w;
        });
    }

    /**
     * 클라이언트 IP. 운영 경로는 앱 → Cloudflare → ALB → 태스크이고, ALB 는 직접 접근도 받는다(보안 그룹 0.0.0.0/0).
     *
     * <ol>
     *   <li>ALB 가 {@code X-Forwarded-For} <b>끝</b>에 붙인 값 = ALB 에 붙은 주소. 앞쪽은 클라이언트가 지어낼 수 있다</li>
     *   <li>그 주소가 Cloudflare 엣지면 Cloudflare 가 덮어쓴 {@code CF-Connecting-IP} 가 클라이언트다
     *       (없으면 Cloudflare 가 엣지 바로 앞에 덧붙인 값). 엣지를 거치지 않은 요청의 CF-Connecting-IP 는 믿지 않는다</li>
     *   <li>아니면 그 주소가 곧 클라이언트(직접 접근). 헤더가 없으면 접속 주소(로컬·테스트)</li>
     * </ol>
     *
     * <p>원본 요청에서 읽는다 — 운영은 {@code server.forward-headers-strategy=framework} 라 ForwardedHeaderFilter 가
     * X-Forwarded-For 를 숨기고 접속 주소를 그 <b>첫 값</b>(위조 가능)으로 바꾼 요청을 넘긴다.
     */
    public static String clientIp(HttpServletRequest request) {
        HttpServletRequest raw = unwrap(request);
        List<String> chain = forwardedChain(raw.getHeader("X-Forwarded-For"));
        if (chain.isEmpty()) {
            String remote = raw.getRemoteAddr();
            return (remote != null) ? remote : "unknown";
        }
        String peer = chain.get(chain.size() - 1);
        if (!CloudflareRanges.contains(peer)) return peer;

        String connecting = raw.getHeader("CF-Connecting-IP");
        if (connecting != null && !connecting.isBlank()) return connecting.trim();
        return (chain.size() >= 2) ? chain.get(chain.size() - 2) : peer;
    }

    private static HttpServletRequest unwrap(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) current = wrapper.getRequest();
        return (current instanceof HttpServletRequest http) ? http : request;
    }

    private static List<String> forwardedChain(String header) {
        if (header == null || header.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String part : header.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) values.add(v);
        }
        return values;
    }

    private static String key(String ip) {
        return (ip == null || ip.isBlank()) ? "unknown" : ip;
    }

    private static final class Window {
        final long startNanos;
        final AtomicInteger failures = new AtomicInteger();

        Window(long startNanos) { this.startNanos = startNanos; }
    }
}
