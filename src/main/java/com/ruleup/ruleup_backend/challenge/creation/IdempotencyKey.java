package com.ruleup.ruleup_backend.challenge.creation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 생성 API 멱등 키(idempotency_keys) — (user_id, idempotency_key) DB 유니크.
 * 재배포 시 유실되는 인메모리(Caffeine)는 금지(스펙 확정) — 네트워크 재시도로 방이 2개 생기는 사고 차단.
 *  - 동일 키 + 동일 request_hash → 보관한 201 응답 스냅샷 재응답
 *  - 동일 키 + 다른 request_hash → 409 IDEMPOTENCY_CONFLICT
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, length = 36, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "response_snapshot", columnDefinition = "json")
    private String responseSnapshot;     // 최초 201 응답 JSON

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id")
    private UUID challengeId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static IdempotencyKey of(UUID userId, String key, String requestHash,
                                    String responseSnapshot, UUID challengeId) {
        IdempotencyKey k = new IdempotencyKey();
        k.userId = userId;
        k.idempotencyKey = key;
        k.requestHash = requestHash;
        k.responseSnapshot = responseSnapshot;
        k.challengeId = challengeId;
        return k;
    }

    public boolean sameRequest(String hash) { return requestHash.equals(hash); }
}
