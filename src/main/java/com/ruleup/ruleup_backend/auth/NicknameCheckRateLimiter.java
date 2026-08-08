package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * POST /api/v1/nicknames/check 호출 제한 (인메모리, EC2 단일 인스턴스 기준).
 * 무인증 엔드포인트라 사용자 대신 클라이언트 IP로 센다 — 닉네임 존재 열거·부하 방지.
 *
 * <p>한도가 넉넉한 이유: 이 API는 온보딩에서 입력할 때마다 호출되고, 모바일 캐리어 NAT
 * 뒤에서는 여러 사용자가 같은 IP로 묶인다. 정상 입력을 막지 않으면서 스크립트 열거만
 * 의미 있게 느려지는 선을 잡았다.
 */
@Component
public class NicknameCheckRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private final int maxPerMinute;

    // 클라이언트 IP -> 현재 1분 윈도우(시작 시각 + 누적 횟수)
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public NicknameCheckRateLimiter(
            @Value("${app.nickname-check.max-per-minute:60}") int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
    }

    public void check(HttpServletRequest request) {
        String key = clientIp(request);
        long now = Instant.now().toEpochMilli();
        // 만료된 다른 IP 윈도우를 이참에 정리 — 맵이 distinct IP 수만큼 무한 증가하는 것 방지
        windows.values().removeIf(w -> now - w.startMillis >= WINDOW_MILLIS);
        Window w = windows.compute(key, (k, old) -> {
            if (old == null || now - old.startMillis >= WINDOW_MILLIS) {
                return new Window(now, 1);   // 새 윈도우 시작
            }
            old.count++;
            return old;
        });
        if (w.count > maxPerMinute) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    /**
     * ALB 뒤에서는 remoteAddr 이 로드밸런서라 모든 요청이 한 키로 뭉친다 →
     * X-Forwarded-For 의 첫 항목(최초 클라이언트)을 우선 사용한다.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) return first;
        }
        String remote = request.getRemoteAddr();
        return (remote != null) ? remote : "unknown";
    }

    private static class Window {
        long startMillis;
        int count;

        Window(long startMillis, int count) {
            this.startMillis = startMillis;
            this.count = count;
        }
    }
}
