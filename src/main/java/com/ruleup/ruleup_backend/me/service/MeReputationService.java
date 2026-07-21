package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.me.dto.MeReputationResponse;
import com.ruleup.ruleup_backend.reputation.ReputationBands;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.reputation.ReputationSnapshotRepository;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 매너 온도 상세(마이프로필 §6.1): 현재 온도 + 밴드 라벨 + 다음 목표 진행 바 + 최근 변동(스냅샷 10건). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeReputationService {

    private static final int RECENT_CHANGES = 10;

    private final ReputationScoreRepository scoreRepository;
    private final ReputationSnapshotRepository snapshotRepository;

    public MeReputationResponse reputation(UUID userId) {
        BigDecimal current = scoreRepository.findById(userId)
                .map(ReputationScore::getMannerTemperature)
                .orElse(ReputationScore.INITIAL_TEMPERATURE);

        ReputationBands.NextTier nt = ReputationBands.nextTier(current);
        List<MeReputationResponse.Change> changes = snapshotRepository
                .findByUserIdOrderBySnapshotDateDesc(userId, PageRequest.of(0, RECENT_CHANGES)).stream()
                .map(s -> new MeReputationResponse.Change(
                        s.getSnapshotDate().toString(), s.getTemperature(), s.getDelta(), s.getLabel()))
                .toList();

        return new MeReputationResponse(
                current, ReputationBands.bandLabel(current),
                new MeReputationResponse.NextTier(nt.target(), nt.progressRate(), nt.label()),
                changes);
    }
}
