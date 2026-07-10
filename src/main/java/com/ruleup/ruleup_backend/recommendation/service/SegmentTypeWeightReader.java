package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;
import com.ruleup.ruleup_backend.recommendation.repository.SegmentTypeWeightRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 축(type)별 학습 가중치 읽기 + 캐시 경계(§8.1 저장·조회 / §9 캐시 경계).
 *  - of(type)  : 축 단위로 캐싱. 배치가 학습·저장한 값을 읽고, 없으면 prior 폴백(학습 전·설정 누락 방어).
 *  - evictAll  : 배치 재계산 커밋 후 호출 → 무효화. 점수 캐시와 함께 비워 정합을 맞춘다.
 * {@link SegmentScoreReader}와 동일하게, self-invocation 캐시 무력화를 피하려 별도 빈으로 둔다.
 */
@Component
@RequiredArgsConstructor
public class SegmentTypeWeightReader {

    static final String CACHE = "segmentWeights";

    private final SegmentTypeWeightRepository weightRepo;

    /** 학습된 w(type). 저장 행이 없으면 prior 로 폴백. */
    @Transactional(readOnly = true)
    @Cacheable(value = CACHE, key = "#type.name()")
    public double of(SegmentType type) {
        return weightRepo.findById(type)
                .map(w -> w.getWeight().doubleValue())
                .orElseGet(() -> SegmentWeightPolicy.prior(type));
    }

    /** 가중치 캐시 전체 무효화(배치 재계산 직후). */
    @CacheEvict(value = CACHE, allEntries = true)
    public void evictAll() {
        // 캐시 무효화만 수행(본문 없음).
    }
}
