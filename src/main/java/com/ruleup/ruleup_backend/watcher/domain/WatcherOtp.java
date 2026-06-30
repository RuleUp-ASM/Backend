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
 * 비유저 동의 1단계 SMS OTP. 코드는 해시로만 저장(평문 금지), 번호는 암호화 저장.
 * 야간 디퍼 예외 — 발송은 즉시(§5.9/§8.2).
 */
@Entity
@Table(name = "WatcherOtp")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatcherOtp extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "invitationId", nullable = false, updatable = false)
    private UUID invitationId;

    @Column(name = "phoneEnc", nullable = false)
    private byte[] phoneEnc;

    @Column(name = "phoneHash", nullable = false, length = 64)
    private String phoneHash;

    @Column(name = "codeHash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expiresAt", nullable = false)
    private Instant expiresAt;

    @Column(name = "resendAvailableAt", nullable = false)
    private Instant resendAvailableAt;

    @Column(name = "consumedAt")
    private Instant consumedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static WatcherOtp issue(UUID invitationId, byte[] phoneEnc, String phoneHash, String codeHash,
                                   Instant expiresAt, Instant resendAvailableAt) {
        WatcherOtp o = new WatcherOtp();
        o.id = UuidGenerator.generate();
        o.invitationId = invitationId;
        o.phoneEnc = phoneEnc;
        o.phoneHash = phoneHash;
        o.codeHash = codeHash;
        o.expiresAt = expiresAt;
        o.resendAvailableAt = resendAvailableAt;
        return o;
    }

    public boolean isExpired(Instant now) { return now.isAfter(expiresAt); }
    public boolean isConsumed() { return consumedAt != null; }
    public boolean matches(String candidateHash) { return codeHash.equals(candidateHash); }
    public void consume(Instant now) { this.consumedAt = now; }
}
