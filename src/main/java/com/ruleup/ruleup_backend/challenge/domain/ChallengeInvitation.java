package com.ruleup.ruleup_backend.challenge.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "challenge_invitations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeInvitation extends AssignedIdEntity {
    @Id @JdbcTypeCode(SqlTypes.BINARY) private UUID id;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(name = "challenge_id", nullable = false) private UUID challengeId;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(name = "inviter_id", nullable = false) private UUID inviterId;
    @Column(name = "token_hash", nullable = false, columnDefinition = "binary(32)") private byte[] tokenHash;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "used_at") private Instant usedAt;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", nullable = false) private Instant createdAt;

    public static ChallengeInvitation create(UUID challengeId, UUID inviterId, byte[] tokenHash, Instant expiresAt) {
        ChallengeInvitation invitation = new ChallengeInvitation();
        invitation.id = UuidGenerator.generate();
        invitation.challengeId = challengeId;
        invitation.inviterId = inviterId;
        invitation.tokenHash = tokenHash;
        invitation.expiresAt = expiresAt;
        return invitation;
    }
}
