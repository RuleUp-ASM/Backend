package com.ruleup.ruleup_backend.auth.domain;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.user.domain.User;
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
 * Refresh Token (refresh_tokens 테이블). User와 N:1.
 * - 원문 미저장: SHA-256 32바이트(BINARY(32))만 저장.
 * - 회전: 최초 발급부터 회전된 모든 토큰이 같은 familyId 를 공유하고,
 *   새 토큰의 parentTokenId 에 직전 토큰 ID를 남긴다.
 * - 재사용 감지: 이미 revoke된 토큰이 다시 제출되면 reuseDetectedAt 기록 후
 *   해당 family 전체를 revoke 한다 (탈취 대응).
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)        // 여러 토큰 → 한 User
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 최초 발급~회전 토큰이 공유하는 패밀리 ID. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    /** 직전(회전 전) 토큰 ID. 최초 발급 토큰은 NULL. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "parent_token_id", updatable = false)
    private UUID parentTokenId;

    /** SHA-256 결과 32바이트 (원문 미저장). */
    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** 폐기된 토큰이 다시 제출된 시각 (재사용 감지). */
    @Column(name = "reuse_detected_at")
    private Instant reuseDetectedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 최초 발급 — 새 family 시작. */
    public static RefreshToken issue(User user, byte[] tokenHash, Instant expiresAt) {
        return issue(user, tokenHash, expiresAt, UuidGenerator.generate(), null);
    }

    /** 회전 발급 — 기존 family 유지 + 직전 토큰 연결. */
    public static RefreshToken issue(User user, byte[] tokenHash, Instant expiresAt,
                                     UUID familyId, UUID parentTokenId) {
        RefreshToken t = new RefreshToken();
        t.id = UuidGenerator.generate();
        t.user = user;
        t.familyId = familyId;
        t.parentTokenId = parentTokenId;
        t.tokenHash = tokenHash;
        t.expiresAt = expiresAt;
        return t;
    }

    public void revoke() {
        if (revokedAt == null) revokedAt = Instant.now();
    }

    /** 재사용 감지 표시 — chk 제약상 revoke를 함께 보장한다. */
    public void markReuseDetected() {
        revoke();
        this.reuseDetectedAt = Instant.now();
    }

    public boolean isRevoked() { return revokedAt != null; }
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
}
