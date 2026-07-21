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

/** 친구 초대 코드(유저당 1개, 멱등 생성). */
@Entity
@Table(name = "InviteCode")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InviteCode extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "code", nullable = false, updatable = false, length = 6)
    private String code;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static InviteCode of(UUID userId, String code) {
        InviteCode c = new InviteCode();
        c.id = UuidGenerator.generate();
        c.userId = userId;
        c.code = code;
        return c;
    }
}
