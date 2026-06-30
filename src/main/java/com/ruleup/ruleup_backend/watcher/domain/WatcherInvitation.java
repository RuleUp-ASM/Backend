package com.ruleup.ruleup_backend.watcher.domain;

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
import java.util.UUID;

/**
 * 감시자 초대 토큰(만료 7일). 공개 진입(GET /watchers/invitations/{token})은 이 테이블로만 접근한다
 * (PII가 든 Watcher 테이블을 공개 경로에서 직접 건드리지 않기 위함 — §5.9).
 */
@Entity
@Table(name = "WatcherInvitation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatcherInvitation extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "token", nullable = false, updatable = false, length = 64)
    private String token;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "inviterUserId", nullable = false, updatable = false)
    private UUID inviterUserId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InvitationStatus status;

    @Column(name = "expiresAt", nullable = false)
    private Instant expiresAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static WatcherInvitation create(UUID inviterUserId, UUID challengeId, String token, Instant expiresAt) {
        WatcherInvitation i = new WatcherInvitation();
        i.id = UuidGenerator.generate();
        i.token = token;
        i.inviterUserId = inviterUserId;
        i.challengeId = challengeId;
        i.status = InvitationStatus.INVITED;
        i.expiresAt = expiresAt;
        return i;
    }

    public boolean isExpired(Instant now) { return now.isAfter(expiresAt); }
    public boolean isConsumed() { return status == InvitationStatus.CONSENTED; }

    public void markConsented() { this.status = InvitationStatus.CONSENTED; }
    public void markRevoked()   { this.status = InvitationStatus.REVOKED; }
    public void markExpired()   { this.status = InvitationStatus.EXPIRED; }
}
