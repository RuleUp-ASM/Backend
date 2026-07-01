package com.ruleup.ruleup_backend.recommendation.repository;

import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 루틴 아웃컴 이력 접근. 수집 배치의 고수위 워터마크 + upsert 조회. */
public interface RoutineOutcomeRepository extends JpaRepository<RoutineOutcome, UUID> {

    /** 마지막으로 수집한 확정 시각(다음 수집 시작점). 없으면 null → 소급 윈도우로 시작. */
    @Query("SELECT MAX(o.confirmedAt) FROM RoutineOutcome o")
    Instant findMaxConfirmedAt();

    /** upsert 키 조회(멤버×날짜 1행). */
    Optional<RoutineOutcome> findByChallengeIdAndUserIdAndTargetDate(UUID challengeId, UUID userId, java.time.LocalDate targetDate);
}
