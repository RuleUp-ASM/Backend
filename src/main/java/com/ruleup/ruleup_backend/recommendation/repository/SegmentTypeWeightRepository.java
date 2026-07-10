package com.ruleup.ruleup_backend.recommendation.repository;

import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;
import com.ruleup.ruleup_backend.recommendation.domain.SegmentTypeWeight;
import org.springframework.data.jpa.repository.JpaRepository;

/** SegmentTypeWeight 접근. 축별 학습 가중치 조회 + 배치 전량 재작성. */
public interface SegmentTypeWeightRepository extends JpaRepository<SegmentTypeWeight, SegmentType> {
}
