package com.ruleup.ruleup_backend.challenge.recommendation;

import com.redis.testcontainers.RedisContainer;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class RecommendationRateLimiterRedisIT {
    @Container static RedisContainer redis=new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    @Test void multipleInstancesShareWindowAndDoNotExtendTtl() {
        var connection=new LettuceConnectionFactory(redis.getHost(),redis.getMappedPort(6379));
        connection.afterPropertiesSet();connection.start();
        try {
            var template=new StringRedisTemplate(connection);
            var first=new RecommendationRateLimiter(template,new SimpleMeterRegistry());
            var second=new RecommendationRateLimiter(template,new SimpleMeterRegistry());
            String user=UUID.randomUUID().toString(),key="rate:challenge:draft:"+user;
            first.check(user);
            assertThat(template.getExpire(key,TimeUnit.SECONDS)).isBetween(59L,60L);
            template.expire(key,30,TimeUnit.SECONDS);
            for(int i=1;i<10;i++)(i%2==0?first:second).check(user);
            assertThatThrownBy(()->second.check(user)).isInstanceOf(BusinessException.class);
            assertThat(template.getExpire(key,TimeUnit.SECONDS)).isBetween(29L,30L);
            template.delete(key);
            assertThatCode(()->second.check(user)).doesNotThrowAnyException();
        } finally {connection.destroy();}
    }
}
