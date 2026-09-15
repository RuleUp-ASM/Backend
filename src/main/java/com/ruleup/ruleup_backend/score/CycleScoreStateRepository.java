package com.ruleup.ruleup_backend.score;
import com.ruleup.ruleup_backend.score.domain.CycleScoreState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CycleScoreStateRepository extends JpaRepository<CycleScoreState,CycleScoreState.Key> {
    Optional<CycleScoreState> findFirstByUserIdAndChallengeIdAndClosedAtIsNotNullOrderByStartedOnDesc(UUID userId, UUID challengeId);
    List<CycleScoreState> findByUserIdOrderByChallengeIdAscStartedOnAsc(UUID userId);
}
