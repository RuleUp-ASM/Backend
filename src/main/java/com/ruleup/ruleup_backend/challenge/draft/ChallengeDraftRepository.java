package com.ruleup.ruleup_backend.challenge.draft;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** challenge_drafts 접근. */
public interface ChallengeDraftRepository extends JpaRepository<ChallengeDraft, UUID> {

    Optional<ChallengeDraft> findByIdAndUserId(UUID id, UUID userId);

    /** 만료 초안 일괄 삭제(라이프사이클 배치). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ChallengeDraft d WHERE d.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
