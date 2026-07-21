package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.me.dto.MeReputationHistoryResponse;
import com.ruleup.ruleup_backend.reputation.MilestoneRepository;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 평판 히스토리(마이프로필): 역대 최고 온도 + 마일스톤 피드(시간 역순, 상한 50). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeReputationHistoryService {

    private static final int MAX_MILESTONES = 50;

    private final ReputationScoreRepository scoreRepository;
    private final MilestoneRepository milestoneRepository;

    public MeReputationHistoryResponse history(UUID userId) {
        ReputationScore score = scoreRepository.findById(userId).orElse(null);

        // peak: 기록이 있으면 그 값, 없으면 현재 온도(달성일 미상).
        BigDecimal peakTemp = (score != null && score.getPeakTemperature() != null)
                ? score.getPeakTemperature()
                : (score != null ? score.getMannerTemperature() : ReputationScore.INITIAL_TEMPERATURE);
        String peakAt = (score != null && score.getPeakAchievedAt() != null)
                ? score.getPeakAchievedAt().toString() : null;

        List<MeReputationHistoryResponse.Milestone> milestones = milestoneRepository
                .findByUserIdOrderByAchievedAtDescCreatedAtDesc(userId, PageRequest.of(0, MAX_MILESTONES)).stream()
                .map(m -> new MeReputationHistoryResponse.Milestone(
                        m.getType().name(), m.getLabel(), m.getAchievedAt().toString()))
                .toList();

        return new MeReputationHistoryResponse(
                new MeReputationHistoryResponse.Peak(peakTemp, peakAt), milestones);
    }
}
