package com.ruleup.ruleup_backend.auth.review;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
     * 클라이언트 IP — ALB 가 덧붙인 {@code X-Forwarded-For} 의 <b>마지막</b> 값. 앞쪽은 클라이언트가 지어낼 수 있다.
     * 헤더가 없으면 접속 주소(로컬·테스트).
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String v = parts[i].trim();
                if (!v.isEmpty()) return v;
            }
        }
        String remote = request.getRemoteAddr();
        return (remote != null) ? remote : "unknown";
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
