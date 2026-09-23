package com.ruleup.ruleup_backend.recommendation.repository;

import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;
import com.ruleup.ruleup_backend.recommendation.domain.TemplateSegmentScore;
import com.ruleup.ruleup_backend.recommendation.domain.TemplateSegmentScoreId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** TemplateSegmentScore 접근. 세그먼트별 점수 조회 + 선택 피드백 upsert. */
public interface TemplateSegmentScoreRepository
        extends JpaRepository<TemplateSegmentScore, TemplateSegmentScoreId> {

    /** 한 세그먼트(예: GENDER=MALE)의 템플릿별 점수 전부. */
    List<TemplateSegmentScore> findBySegmentTypeAndSegmentValue(SegmentType segmentType, String segmentValue);
}
