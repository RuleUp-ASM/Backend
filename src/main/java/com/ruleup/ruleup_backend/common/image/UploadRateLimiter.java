package com.ruleup.ruleup_backend.common.image;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자별 이미지 업로드 횟수 제한 (인메모리).
 * EC2 단일 인스턴스 기준. 1분 안에 MAX_PER_MINUTE 회를 넘으면 막는다.
 */
@Component
public class UploadRateLimiter {

    private static final int MAX_PER_MINUTE = 10;
    private static final long WINDOW_MILLIS = 60_000;

    // userId -> 현재 1분 윈도우(시작 시각 + 누적 횟수)
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void check(String userId) {
        long now = Instant.now().toEpochMilli();
        Window w = windows.compute(userId, (key, old) -> {
            if (old == null || now - old.startMillis >= WINDOW_MILLIS) {
                return new Window(now, 1);   // 새 윈도우 시작
            }
            old.count++;                     // 같은 윈도우 안에서 증가
            return old;
        });
        if (w.count > MAX_PER_MINUTE) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
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