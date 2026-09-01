package com.ruleup.ruleup_backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * Caffeine(JVM 로컬) 기반 캐시 매니저.
 *  - 값은 힙에 객체 그대로 저장 → 직렬화 없이 record/POJO 라운드트립 안전.
 *  - 캐시별 TTL을 따로 지정. {@code segmentScores}는 배치가 명시적으로 evict하므로 TTL은 백스톱.
 *
 * <p>{@code @Profile("!test")} 인 이유: 테스트는 application-test.yaml의 {@code spring.cache.type: simple} 로
 * 인메모리 ConcurrentMapCacheManager를 쓰게 두어 캐시 구성 차이를 줄인다. test 프로파일에선 이 빈이 빠진다.
 *
 * <p>ElastiCache(Redis)를 제거하고 Caffeine으로 전환. 캐시는 인스턴스별 로컬이며,
 * segmentScores는 배치가 각 인스턴스에서 재계산/evict하고 categories는 정적 마스터라 인스턴스 간 불일치 문제 없음.
 */
@Configuration
@Profile("!test")
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // 등록되지 않은 이름의 캐시가 생기면 이 기본 스펙으로 만든다(원래 RedisCacheManager cacheDefaults(1h) 대응).
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofHours(1)));

        // 배치가 evict하므로 TTL은 보조 안전장치(배치 누락 시 자가치유).
        manager.registerCustomCache("segmentScores", Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofHours(6))
                .build());

        // 특성 가중치(§8.1) — 5행. 점수와 같은 배치가 evict하므로 TTL은 백스톱.
        manager.registerCustomCache("segmentWeights", Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(Duration.ofHours(6))
                .build());

        // 관심 카테고리 마스터 — 거의 안 변함.
        manager.registerCustomCache("categories", Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(Duration.ofHours(24))
                .build());

        // 홈 카테고리 그리드 진행 중 수(탐색 §2.2). 상태 전환 배치가 ChallengeGridChanged 로 즉시 evict 하므로
        // TTL 은 백스톱이다. 다만 Caffeine 은 인스턴스 로컬이라 evict 가 자기 인스턴스에만 닿는다 —
        // 다른 인스턴스가 따라오는 속도가 곧 TTL 이라, 10분이면 "수가 한참 안 바뀐다"로 보인다. 1분으로 줄인다.
        manager.registerCustomCache("challengeCategories", Caffeine.newBuilder()
                .maximumSize(10)
                .expireAfterWrite(Duration.ofMinutes(1))
                .build());

        // 홈 인기 랭킹은 여기 없다 — Redis ZSET 이 소유한다(TrendingRankingSource).
        // 인스턴스 로컬 캐시로 두면 서버가 늘 때 같은 사용자가 새로고침마다 다른 순위를 본다.

        return manager;
    }
}
