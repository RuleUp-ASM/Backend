package com.ruleup.ruleup_backend.reputation;
import com.ruleup.ruleup_backend.reputation.domain.*;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** reputation_scores 접근. */
public interface ReputationScoreRepository extends JpaRepository<ReputationScore, UUID> {

    /** 온도 배치 대상: 아직 오늘자 계산이 안 된 유저 id(멱등 가드). */
    @Query("SELECT r.userId FROM ReputationScore r " +
            "WHERE r.lastCalculatedDate IS NULL OR r.lastCalculatedDate < :today")
    List<UUID> findUserIdsToCalculate(@Param("today") LocalDate today);

    /** 계산 대상 1건을 비관적 락으로 선점(동일 유저 동시 계산 방지 — EMA는 멱등이 아님). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReputationScore r WHERE r.userId = :userId")
    Optional<ReputationScore> findByUserIdForUpdate(@Param("userId") UUID userId);
}
