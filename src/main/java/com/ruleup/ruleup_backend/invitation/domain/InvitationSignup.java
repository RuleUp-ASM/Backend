package com.ruleup.ruleup_backend.invitation.domain;

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

/** 피초대자 가입 기록(초대 현황). inviteeUserId 유니크 — 한 사람은 1회만 피초대 기록. */
@Entity
@Table(name = "InvitationSignup")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvitationSignup extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "inviterUserId", nullable = false, updatable = false)
    private UUID inviterUserId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "inviteeUserId", nullable = false, updatable = false)
    private UUID inviteeUserId;

    @Column(name = "occurredAt", nullable = false, updatable = false)
    private Instant occurredAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static InvitationSignup of(UUID inviterUserId, UUID inviteeUserId, Instant occurredAt) {
        InvitationSignup s = new InvitationSignup();
        s.id = UuidGenerator.generate();
        s.inviterUserId = inviterUserId;
        s.inviteeUserId = inviteeUserId;
        s.occurredAt = occurredAt;
        return s;
    }
}
