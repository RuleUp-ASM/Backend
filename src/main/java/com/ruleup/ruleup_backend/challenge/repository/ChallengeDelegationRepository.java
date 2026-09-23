package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeDelegation;
import com.ruleup.ruleup_backend.challenge.domain.DelegationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 방장 위임 요청 접근(§7-2). */
public interface ChallengeDelegationRepository extends JpaRepository<ChallengeDelegation, UUID> {

    Optional<ChallengeDelegation> findByIdAndChallengeId(UUID id, UUID challengeId);

    /** 챌린지당 유효(PENDING) 요청 존재 여부(중복 요청 차단). */
    boolean existsByChallengeIdAndStatus(UUID challengeId, DelegationStatus status);

    /** 만료 배치: expiresAt 이 지난 PENDING 요청을 EXPIRED 로 일괄 전환(멱등). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChallengeDelegation d SET d.status = com.ruleup.ruleup_backend.challenge.domain.DelegationStatus.EXPIRED, "
            + "d.resolvedAt = :now "
            + "WHERE d.status = com.ruleup.ruleup_backend.challenge.domain.DelegationStatus.PENDING AND d.expiresAt <= :now")
    int expirePendingDueBefore(@Param("now") Instant now);
}
