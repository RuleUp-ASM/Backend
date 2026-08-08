package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 무인증 닉네임 확인 API 의 호출 제한 — 닉네임 존재 열거·부하 방지(닉네임 확인 명세).
 * 통합 테스트는 전부 같은 IP라 한도를 낮게 두면 서로 간섭한다 → 여기서 한도만 따로 검증한다.
 */
class NicknameCheckRateLimiterTest {

    private static MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    @DisplayName("한도까지는 통과하고 한 번 더 부르면 429 TOO_MANY_REQUESTS")
    void blocks_after_limit() {
        NicknameCheckRateLimiter limiter = new NicknameCheckRateLimiter(3);
        MockHttpServletRequest request = requestFrom("10.0.0.1");

        for (int i = 0; i < 3; i++) {
            int attempt = i;
            assertThatCode(() -> limiter.check(request))
                    .as("한도 안 %d번째 호출", attempt + 1).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.check(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThat(ErrorCode.TOO_MANY_REQUESTS.getStatus().value()).isEqualTo(429);
    }

    @Test
    @DisplayName("IP가 다르면 서로의 한도를 잡아먹지 않는다")
    void counts_per_ip() {
        NicknameCheckRateLimiter limiter = new NicknameCheckRateLimiter(1);

        limiter.check(requestFrom("10.0.0.1"));
        assertThatCode(() -> limiter.check(requestFrom("10.0.0.2"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(requestFrom("10.0.0.1")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("ALB 뒤에서는 X-Forwarded-For 의 첫 IP로 센다 — 안 그러면 전원이 한 키로 뭉친다")
    void uses_forwarded_client_ip() {
        NicknameCheckRateLimiter limiter = new NicknameCheckRateLimiter(1);

        MockHttpServletRequest first = requestFrom("10.0.0.9");     // 같은 LB
        first.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.9");
        MockHttpServletRequest second = requestFrom("10.0.0.9");    // 같은 LB, 다른 클라이언트
        second.addHeader("X-Forwarded-For", "203.0.113.2, 10.0.0.9");

        limiter.check(first);
        assertThatCode(() -> limiter.check(second))
                .as("remoteAddr 로만 세면 여기서 이미 막힌다").doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(first)).isInstanceOf(BusinessException.class);
    }
}
