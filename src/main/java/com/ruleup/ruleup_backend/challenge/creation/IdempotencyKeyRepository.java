package com.ruleup.ruleup_backend.challenge.creation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** idempotency_keys 접근. */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
