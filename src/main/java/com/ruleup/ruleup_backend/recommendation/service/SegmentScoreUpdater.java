package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.recommendation.domain.TemplateSegmentScore;
import com.ruleup.ruleup_backend.recommendation.domain.TemplateSegmentScoreId;
import com.ruleup.ruleup_backend.recommendation.repository.TemplateSegmentScoreRepository;
import com.ruleup.ruleup_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 선택 피드백 루프(웜업 데이터 축적). 챌린지 생성 시 그 유저의 각 세그먼트에 대해 templateId 점수 +1 upsert.
 *  - 데이터가 쌓일수록 세그먼트별 인기 템플릿이 추천 상위로.
 */
@Service
@RequiredArgsConstructor
public class SegmentScoreUpdater {

    private static final BigDecimal SELECTION_WEIGHT = BigDecimal.ONE;

    private final TemplateSegmentScoreRepository scoreRepo;
    private final SegmentResolver segmentResolver;

    @Transactional
    public void recordSelection(User user, Long templateId) {
        if (templateId == null || user == null) return;   // 직접 입력(템플릿 매칭 실패)은 점수 없음
        for (Segment seg : segmentResolver.resolve(user)) {
            TemplateSegmentScoreId id = new TemplateSegmentScoreId(seg.type(), seg.value(), templateId);
            TemplateSegmentScore s = scoreRepo.findById(id)
                    .orElseGet(() -> TemplateSegmentScore.of(seg.type(), seg.value(), templateId));
            s.addScore(SELECTION_WEIGHT);
            scoreRepo.save(s);
        }
    }
}
