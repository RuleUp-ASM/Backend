package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.generator.EventType;
import com.ruleup.ruleup_backend.common.AssignedIdEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh Token (RefreshToken 테이블). User와 N:1.
 * 토큰 원문이 아니라 hash만 저장. revokedAt으로 강제 무효화 표시.
 */
@Entity
@Table(name = "RefreshToken")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)        // 여러 토큰 → 한 User
    @JoinColumn(name = "userId", nullable = false)
    private User user;

    @Column(name = "tokenHash", nullable = false)
    private String tokenHash;

    @Column(name = "expiresAt", nullable = false)
    private Instant expiresAt;

    @Column(name = "revokedAt")
    private Instant revokedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static RefreshToken issue(User user, String tokenHash, Instant expiresAt) {
        RefreshToken t = new RefreshToken();
        t.id = UuidGenerator.generate();
        t.user = user;
        t.tokenHash = tokenHash;
        t.expiresAt = expiresAt;
        return t;
    }

    public void revoke() { this.revokedAt = Instant.now(); }
    public boolean isRevoked() { return revokedAt != null; }
}