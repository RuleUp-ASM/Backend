package com.ruleup.ruleup_backend.challenge.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 방장 위임 요청 (ChallengeDelegation 테이블, 생성 및 라이프사이클 스펙 §7-2).
 *  - 요청자(requesterId=OWNER)가 대상(targetUserId=MANAGER)에게 위임을 건다.
 *  - 챌린지당 유효(PENDING) 요청은 1건. 요청은 생성 +7일에 만료(배치가 EXPIRED 전환).
 *  - 수락 시점에 role swap 트랜잭션으로 성립(OWNER는 항상 정확히 1명 불변식).
 */
@Entity
@Table(name = "ChallengeDelegation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeDelegation extends AssignedIdEntity {

    /** 위임 요청 유효 기간(생성 +7일). */
    public static final int EXPIRY_DAYS = 7;

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "requesterId", nullable = false, updatable = false)
    private UUID requesterId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "targetUserId", nullable = false, updatable = false)
    private UUID targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DelegationStatus status;

    @Column(name = "expiresAt", nullable = false)
    private Instant expiresAt;

    @Column(name = "resolvedAt")
    private Instant resolvedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public static ChallengeDelegation request(UUID challengeId, UUID requesterId, UUID targetUserId, Instant now) {
        ChallengeDelegation d = new ChallengeDelegation();
        d.id = UuidGenerator.generate();
        d.challengeId = challengeId;
        d.requesterId = requesterId;
        d.targetUserId = targetUserId;
        d.status = DelegationStatus.PENDING;
        d.expiresAt = now.plus(EXPIRY_DAYS, ChronoUnit.DAYS);
        return d;
    }

    public boolean isPending() { return status == DelegationStatus.PENDING; }

    public boolean isExpired(Instant now) { return now.isAfter(expiresAt); }

    public void accept(Instant at) { this.status = DelegationStatus.ACCEPTED; this.resolvedAt = at; }
    public void reject(Instant at) { this.status = DelegationStatus.REJECTED; this.resolvedAt = at; }
    public void cancel(Instant at) { this.status = DelegationStatus.CANCELED; this.resolvedAt = at; }
    public void expire(Instant at) { this.status = DelegationStatus.EXPIRED; this.resolvedAt = at; }
}
