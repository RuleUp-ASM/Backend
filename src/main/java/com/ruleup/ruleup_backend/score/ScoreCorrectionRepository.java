package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.score.domain.ScoreCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ScoreCorrectionRepository extends JpaRepository<ScoreCorrection, UUID> {

    /** 같은 판정을 이미 정정했는지 — 재시도로 점수가 두 번 오르는 것을 막는다. */
    boolean existsByOriginalEventIdAndCorrectionVersion(UUID originalEventId, int correctionVersion);
}
