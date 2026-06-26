package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;
import com.ruleup.ruleup_backend.recommendation.dto.SegmentScoreEntry;
import com.ruleup.ruleup_backend.recommendation.repository.TemplateSegmentScoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 세그먼트별 점수 읽기 + 캐시 경계.
 *  - scoresFor: (세그먼트축, 값) 단위로 Redis 캐싱. 점수는 배치 시점에만 바뀌므로 TTL이 길어도 안전.
 *  - evictAll : 배치 재계산 커밋 후 호출 → 캐시 무효화. 변경 시점 = 무효화 시점이라 thrash 없음.
 * RecommendationService와 별도 빈으로 둔 이유: @Cacheable/@CacheEvict는 자기-호출(self-invocation)에선
 * 프록시를 안 타기 때문에, 캐시 경계는 반드시 다른 빈에 둬야 한다.
 */
@Component
@RequiredArgsConstructor
public class SegmentScoreReader {

    static final String CACHE = "segmentScores";

    private final TemplateSegmentScoreRepository scoreRepo;

    @Transactional(readOnly = true)
    @Cacheable(value = CACHE, key = "#type.name() + ':' + #value")
    public List<SegmentScoreEntry> scoresFor(SegmentType type, String value) {
        return scoreRepo.findBySegmentTypeAndSegmentValue(type, value).stream()
                .map(s -> new SegmentScoreEntry(s.getTemplateId(), s.getScore().doubleValue()))
                .toList();
    }

    /** 세그먼트 점수 캐시 전체 무효화(배치 재계산 직후). */
    @CacheEvict(value = CACHE, allEntries = true)
    public void evictAll() {
        // 캐시 무효화만 수행(본문 없음).
    }
}
