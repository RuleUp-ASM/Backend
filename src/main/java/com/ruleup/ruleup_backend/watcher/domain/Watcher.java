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
 * 감시자 1행(초대 1건당 1행). 수락(유저)/동의(비유저)로 type·channel·연락처가 채워진다.
 * 비유저 연락처는 암호화(contactEnc) 저장 + 생성자에겐 마스킹(contactMasked)만 노출(§5.9).
 */
@Entity
@Table(name = "Watcher")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Watcher extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "invitationId", nullable = false, updatable = false)
    private UUID invitationId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "inviterUserId", nullable = false, updatable = false)
    private UUID inviterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private WatcherType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel")
    private WatcherChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WatcherStatus status;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "watcherUserId")
    private UUID watcherUserId;              // USER 타입

    @Column(name = "contactEnc")
    private byte[] contactEnc;               // NON_USER 연락처(암호화). 해제 시 파기.

    @Column(name = "contactMasked", length = 32)
    private String contactMasked;            // 생성자 노출용

    @Column(name = "displayName", length = 40)
    private String displayName;

    @Column(name = "unsubscribeToken", length = 64)
    private String unsubscribeToken;

    @Column(name = "consentedAt")
    private Instant consentedAt;

    @Column(name = "revokedAt")
    private Instant revokedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "invitedAt", nullable = false, updatable = false)
    private Instant invitedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    /** 초대 발급 시 INVITED 감시자 생성(타입/연락처 미정). */
    public static Watcher invited(UUID invitationId, UUID challengeId, UUID inviterUserId) {
        Watcher w = new Watcher();
        w.id = UuidGenerator.generate();
        w.invitationId = invitationId;
        w.challengeId = challengeId;
        w.inviterUserId = inviterUserId;
        w.status = WatcherStatus.INVITED;
        return w;
    }

    /** 유저 수락(인앱=동의). 챌린지 진행 중이면 즉시 ACTIVE. */
    public void consentAsUser(UUID watcherUserId, String displayName, boolean challengeActive, Instant now) {
        this.type = WatcherType.USER;
        this.channel = WatcherChannel.IN_APP;
        this.watcherUserId = watcherUserId;
        this.displayName = displayName;
        this.consentedAt = now;
        this.status = challengeActive ? WatcherStatus.ACTIVE : WatcherStatus.CONSENTED;
    }

    /** 비유저 동의(OTP 검증 후). 채널 SMS, 연락처 암호화 저장 + 마스킹. */
    public void consentAsNonUser(byte[] contactEnc, String contactMasked, String unsubscribeToken,
                                 boolean challengeActive, Instant now) {
        this.type = WatcherType.NON_USER;
        this.channel = WatcherChannel.SMS;
        this.contactEnc = contactEnc;
        this.contactMasked = contactMasked;
        this.unsubscribeToken = unsubscribeToken;
        this.consentedAt = now;
        this.status = challengeActive ? WatcherStatus.ACTIVE : WatcherStatus.CONSENTED;
    }

    /** 해제(생성자) 또는 수신거부(본인) → REVOKED + 연락처 파기. */
    public void revoke(Instant now) {
        this.status = WatcherStatus.REVOKED;
        this.revokedAt = now;
        this.contactEnc = null;          // 연락처 파기
        this.unsubscribeToken = null;
    }

    public boolean isRevoked()  { return status == WatcherStatus.REVOKED; }
    public boolean isConsented(){ return status == WatcherStatus.CONSENTED || status == WatcherStatus.ACTIVE; }
}
