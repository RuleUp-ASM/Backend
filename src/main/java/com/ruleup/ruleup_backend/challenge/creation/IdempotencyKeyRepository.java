package com.ruleup.ruleup_backend.challenge.creation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * idempotency_keys 접근.
 *
 * <p>동시 최초 요청은 {@link #reserve} 로 키 행 존재를 먼저 보장한 뒤 {@link #lockRow} 로 그 행을 잠그고,
 * 잠금 안에서 request_hash 를 비교한다(백엔드 테크스펙 4-3). 유니크 예외를 catch 한 채
 * rollback-only 트랜잭션을 정상 응답으로 반환하지 않는다 — 그 경로는 커밋 시 500 이 된다.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    /**
     * 키 행 선점(멱등). 이미 있으면 값을 건드리지 않는다 — 뒤에 온 요청은 여기서
     * 앞선 요청의 커밋을 기다렸다가 그 행을 보게 된다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash) "
            + "VALUES (:userId, :key, :requestHash) "
            + "ON DUPLICATE KEY UPDATE request_hash = request_hash", nativeQuery = true)
    void reserve(@Param("userId") UUID userId,
                 @Param("key") String key,
                 @Param("requestHash") String requestHash);

    /** 선점한 행을 잠그고 읽는다. 해시 비교·스냅샷 확정은 이 잠금 안에서만 한다. */
    @Query(value = "SELECT * FROM idempotency_keys WHERE user_id = :userId AND idempotency_key = :key FOR UPDATE",
            nativeQuery = true)
    Optional<IdempotencyKey> lockRow(@Param("userId") UUID userId, @Param("key") String key);
}
