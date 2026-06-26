package com.ruleup.ruleup_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis 기반 캐시 매니저.
 *  - 값 직렬화는 GenericJackson2Json({@code @class} 메타 포함) → record/POJO 라운드트립 안전.
 *  - 캐시별 TTL을 따로 지정. {@code segmentScores}는 배치가 명시적으로 evict하므로 TTL은 백스톱.
 *
 * <p>{@code @Profile("!test")} 인 이유: 테스트는 Testcontainers(MySQL)만 띄우고 Redis는 안 띄운다.
 * test 프로파일에선 이 빈이 빠지고, application-test.yaml의 {@code spring.cache.type: simple} 로
 * 인메모리 ConcurrentMapCacheManager가 자동 구성된다.
 */
@Configuration
@Profile("!test")
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisSerializer.json()))
                .entryTtl(Duration.ofHours(1));

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                // 배치가 evict하므로 TTL은 보조 안전장치(배치 누락 시 자가치유).
                "segmentScores", base.entryTtl(Duration.ofHours(6)),
                // 관심 카테고리 마스터 — 거의 안 변함.
                "categories", base.entryTtl(Duration.ofHours(24))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(perCache)
                .build();
    }
}
