package com.ruleup.ruleup_backend.challenge.explore;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.dto.TrendingResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 홈 실시간 인기 조회 (탐색 백엔드 테크스펙 §11-1).
 *
 * <p>순위는 캐시에서, <b>표시값과 사용자별 값은 DB 에서</b> 읽어 합친다. 인기에는 티어 필터를
 * 적용하지 않는다 — 내 티어로 못 들어가는 방도 보이고 {@code joinable} 로 잠금만 표시한다(정책 §3.1).
 */
@Service
@RequiredArgsConstructor
public class TrendingService {

    private final TrendingRankingCache rankingCache;
    private final ChallengeRepository challengeRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("trending_cache_hit_ratio", this, TrendingService::cacheHitRatio)
                .description("Trending 랭킹 Caffeine 캐시 적중률")
                .register(meterRegistry);
    }

    @Transactional(readOnly = true)
    public TrendingResponse getTrending(UUID userId, String category) {
        String normalized = normalizeCategory(category);
        recordCacheLookup(normalized);
        TrendingRankingCache.Ranking ranking = rankingCache.ranking(normalized);
        if (ranking.entries().isEmpty()) {
            return new TrendingResponse(ranking.calculatedAt(), List.of());
        }

        List<UUID> ids = ranking.entries().stream().map(TrendingRankingCache.Entry::challengeId).toList();
        Map<UUID, Challenge> byId = challengeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Challenge::getId, Function.identity()));
        Tier myTier = displayTier(userId);

        List<TrendingResponse.Item> items = new ArrayList<>();
        int rank = 1;
        for (TrendingRankingCache.Entry entry : ranking.entries()) {
            Challenge c = byId.get(entry.challengeId());
            if (!isCurrentlyVisible(c, normalized)) continue;
            items.add(new TrendingResponse.Item(
                    rank++,
                    c.getId().toString(),
                    c.publicTitle(),          // 심사 중·거부면 AI 임시 제목
                    c.publicImageUrl(),       // 심사 중·거부면 기본 이미지(null)
                    c.getCategory(),
                    c.getParticipantCount(),
                    entry.recentJoins24h(),
                    verificationType(c),
                    (c.getMinTier() != null) ? c.getMinTier().name() : null,
                    joinable(myTier, c),
                    c.getEndDate().toString()));
        }
        return new TrendingResponse(ranking.calculatedAt(), items);
    }

    private void recordCacheLookup(String category) {
        var cache = cacheManager.getCache(TrendingRankingCache.CACHE);
        String key = category == null ? "ALL" : category;
        if (cache != null && cache.get(key) != null) cacheHits.incrementAndGet();
        else cacheMisses.incrementAndGet();
    }

    private double cacheHitRatio() {
        long hits = cacheHits.get();
        long requests = hits + cacheMisses.get();
        return requests == 0 ? 0d : (double) hits / requests;
    }

    /**
     * 랭킹은 최대 1시간 전 스냅샷이므로 응답 직전에 존재 은닉 조건을 다시 확인한다.
     * 공개 범위·모드·상태·카테고리는 캐시 수명 중에도 바뀔 수 있다.
     */
    private boolean isCurrentlyVisible(Challenge c, String category) {
        if (c == null || c.getDeletedAt() != null) return false;
        if (c.getParticipationType() != ParticipationType.GROUP || !"PUBLIC".equals(c.getVisibility())) {
            return false;
        }
        if (c.getStatus() != ChallengeStatus.UPCOMING && c.getStatus() != ChallengeStatus.ACTIVE) {
            return false;
        }
        return category == null || category.equals(c.getCategory());
    }

    /** 표시 티어가 최소 티어 이상인가 — 잠금 아이콘용이며 목록에서 빼는 데는 쓰지 않는다. */
    private boolean joinable(Tier myTier, Challenge c) {
        return c.getMinTier() == null || myTier.ordinal() >= c.getMinTier().ordinal();
    }

    private String verificationType(Challenge c) {
        var config = c.getVerificationConfig();
        return (config != null && config.selectedMethod() == SelectedMethod.AUTO) ? "AUTO" : "MANUAL";
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return null;
        String code = category.trim().toUpperCase();
        if (!InterestCategory.allValid(List.of(code)))
            throw new BusinessException(ErrorCode.INVALID_FILTER_VALUE);
        return code;
    }

    private Tier displayTier(UUID userId) {
        Tier tier = scoreSummaryRepository.findById(userId).map(s -> s.getDisplayTier()).orElse(Tier.BRONZE);
        return (tier == Tier.UNRANKED) ? Tier.BRONZE : tier;
    }
}
