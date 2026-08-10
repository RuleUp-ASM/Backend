package com.ruleup.ruleup_backend.room.repository;

import com.ruleup.ruleup_backend.room.domain.Notice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {
    Optional<Notice> findByIdAndChallengeIdAndDeletedAtIsNull(UUID id, UUID challengeId);
    Optional<Notice> findByIdAndDeletedAtIsNull(UUID id);
    List<Notice> findByChallengeIdAndDeletedAtIsNullOrderByPinnedDescCreatedAtDesc(UUID challengeId);
    List<Notice> findByChallengeIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID challengeId);
    Optional<Notice> findByChallengeIdAndPinnedTrueAndDeletedAtIsNull(UUID challengeId);
    long countByChallengeIdAndDeletedAtIsNull(UUID challengeId);
    long countByChallengeIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndDeletedAtIsNull(
            UUID challengeId, Instant start, Instant end);
}
