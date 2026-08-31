package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.score.domain.CycleScoreState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CycleScoreStateRepository
        extends JpaRepository<CycleScoreState, CycleScoreState.Key> {

    /**
     * 정산 대상 사이클 행을 잠근다. 같은 사용자의 점수 쓰기를 직렬화하는 두 번째 잠금이며,
     * 여러 사이클을 다룰 때는 (challengeId, cycleNo) 오름차순으로 잡아 교착을 피한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM CycleScoreState s
            WHERE s.userId = :userId AND s.challengeId = :challengeId AND s.cycleNo = :cycleNo""")
    Optional<CycleScoreState> findForUpdate(@Param("userId") UUID userId,
                                            @Param("challengeId") UUID challengeId,
                                            @Param("cycleNo") int cycleNo);

    /** 정산 배치의 고수위 워터마크. 별도 상태 테이블 없이 정산 대상 자체에서 읽는다. */
    @Query("SELECT MAX(s.lastJudgedAt) FROM CycleScoreState s")
    java.time.Instant findMaxLastJudgedAt();

    /** 마감 대상 — 사이클이 끝난 날짜를 지났는데 아직 닫히지 않은 행. */
    @Query("""
            SELECT s FROM CycleScoreState s
            WHERE s.closedAt IS NULL AND s.startedOn <= :startedOnOrBefore
            ORDER BY s.challengeId, s.cycleNo""")
    List<CycleScoreState> findClosable(@Param("startedOnOrBefore") LocalDate startedOnOrBefore);
}
