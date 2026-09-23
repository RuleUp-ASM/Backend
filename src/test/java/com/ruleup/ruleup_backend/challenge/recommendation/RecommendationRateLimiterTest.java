package com.ruleup.ruleup_backend.challenge.recommendation;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationRateLimiterTest {
    @Test void redisRejectionDoesNotFallBackAndBypassLimit() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        when(redis.execute(any(),anyList())).thenReturn(List.of(11L,42L));
        var metrics=new SimpleMeterRegistry();
        var limiter=new RecommendationRateLimiter(redis,metrics);
        assertThatThrownBy(()->limiter.check("user")).isInstanceOf(BusinessException.class);
        assertThat(metrics.find("challenge.draft.rate_limiter.fallback").counter()).isNull();
    }

    /** 남은 대기 초는 숫자 자리에 싣는다 — reason 에 넣으면 클라가 문자열을 파싱해야 한다(QA CRE-04). */
    @Test void limitedResponseCarriesRemainingSecondsAsNumber() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        when(redis.execute(any(),anyList())).thenReturn(List.of(11L,42L));
        var limiter=new RecommendationRateLimiter(redis,new SimpleMeterRegistry());
        assertThatThrownBy(()->limiter.check("user"))
                .isInstanceOfSatisfying(BusinessException.class,limited->{
                    assertThat(limited.getRetryAfterSeconds()).isEqualTo(42);
                    assertThat(limited.getDetail()).isNull();
                });
    }

    /** TTL 이 0 이나 음수로 와도 "지금 바로 다시" 라고 말하지 않는다. */
    @Test void remainingSecondsIsAtLeastOne() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        when(redis.execute(any(),anyList())).thenReturn(List.of(11L,-1L));
        var limiter=new RecommendationRateLimiter(redis,new SimpleMeterRegistry());
        assertThatThrownBy(()->limiter.check("user"))
                .isInstanceOfSatisfying(BusinessException.class,
                        limited->assertThat(limited.getRetryAfterSeconds()).isEqualTo(1));
    }

    @Test void fallbackAllowsExactlyTenConcurrentRequestsPerUser() throws Exception {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        when(redis.execute(any(),anyList())).thenThrow(new IllegalStateException("offline"));
        var metrics=new SimpleMeterRegistry();
        var limiter=new RecommendationRateLimiter(redis,metrics);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var calls=IntStream.range(0,40).mapToObj(i->(Callable<Boolean>)()->{
                try{limiter.check("same-user");return true;}catch(BusinessException limited){return false;}
            }).toList();
            int allowed=0;
            for(var result:pool.invokeAll(calls))if(result.get())allowed++;
            assertThat(allowed).isEqualTo(10);
        }
        assertThatCode(()->limiter.check("other-user")).doesNotThrowAnyException();
        assertThat(metrics.find("challenge.draft.rate_limiter.fallback").counter().count()).isPositive();
    }
}
