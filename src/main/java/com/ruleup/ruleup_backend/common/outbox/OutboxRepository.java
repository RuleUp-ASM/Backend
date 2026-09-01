package com.ruleup.ruleup_backend.common.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * 처리할 차례가 된 미처리 건. {@code ix_outbox_pending (processed_at, available_at)} 를 그대로 탄다.
     *
     * <p>오래된 것부터 집는다 — 아웃박스는 순서가 곧 사건의 순서이고, 같은 사용자에게 나가는
     * 고지가 뒤바뀌면 화면에서 시간순이 깨진다.
     */
    @Query("""
            SELECT m FROM OutboxMessage m
             WHERE m.processedAt IS NULL
               AND m.availableAt <= :now
             ORDER BY m.availableAt ASC, m.createdAt ASC
            """)
    List<OutboxMessage> findDue(@Param("now") Instant now, Limit limit);

    Optional<OutboxMessage> findByDedupKey(String dedupKey);

    /**
     * 처리 직전 행 잠금 — 인스턴스가 여러 대일 때 같은 건을 동시에 발행하는 것을 막는다.
     * 락을 얻은 뒤 {@code processedAt} 을 다시 확인해야 한다. 기다리는 동안 상대가 끝냈을 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM OutboxMessage m WHERE m.id = :id")
    Optional<OutboxMessage> findByIdForUpdate(@Param("id") UUID id);

    /** 보관 기간이 지난 처리 완료분 정리용. */
    @Query("""
            SELECT m FROM OutboxMessage m
             WHERE m.processedAt IS NOT NULL
               AND m.processedAt < :threshold
            """)
    List<OutboxMessage> findProcessedBefore(@Param("threshold") Instant threshold, Limit limit);
}
