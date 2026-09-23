package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ChallengeInvitationRepository extends JpaRepository<ChallengeInvitation, UUID> {

    Optional<ChallengeInvitation> findByTokenHash(byte[] tokenHash);

    /**
     * 초대장 1회 소모. {@code used_at IS NULL} 조건부 갱신이라 같은 링크로 두 명이 동시에 수락해도
     * 영향 행이 1인 쪽만 통과한다 — 별도 락 없이 1회성을 DB 수준에서 보장한다.
     *
     * @return 영향받은 행 수. 0이면 이미 누가 소모한 것이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChallengeInvitation i set i.usedAt = :usedAt where i.id = :id and i.usedAt is null")
    int markUsed(@Param("id") UUID id, @Param("usedAt") Instant usedAt);
}
