package com.ruleup.ruleup_backend.push.domain;

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
import java.time.LocalDate;
import java.util.UUID;

/**
 * 고스트 푸시 큐 1건(§8.5, 실시간 권한공백 트리거). 적재(PENDING) → 스윕이 발송(SENT)하거나
 * 대상 토큰 없음/정책상 제외면 SKIPPED. 외부 전송은 트랜잭션 밖(스윕)에서만 한다(watcher 통지와 동일 패턴).
 *  - 멱등: (userId, challengeId, targetDate, type) 유니크로 sync 마다 중복 적재 차단(하루 1건).
 * 연관관계 대신 raw UUID 만 보유.
 */
@Entity
@Table(name = "PushOutbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushOutbox extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    @Column(name = "targetDate", nullable = false, updatable = false)
    private LocalDate targetDate;

    @Column(name = "type", nullable = false, updatable = false, length = 40)
    private String type;                 // SilentPush 타입(예: PERMISSION_REQUIRED)

    @Column(name = "signalType", length = 40)
    private String signalType;           // 권한이 빠진 신호(클라 분기용, 없으면 null)

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PushOutboxStatus status;

    @Column(name = "scheduledAt", nullable = false)
    private Instant scheduledAt;

    @Column(name = "sentAt")
    private Instant sentAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static PushOutbox enqueue(UUID userId, UUID challengeId, LocalDate targetDate,
                                     String type, String signalType, Instant scheduledAt) {
        PushOutbox o = new PushOutbox();
        o.id = UuidGenerator.generate();
        o.userId = userId;
        o.challengeId = challengeId;
        o.targetDate = targetDate;
        o.type = type;
        o.signalType = signalType;
        o.status = PushOutboxStatus.PENDING;
        o.scheduledAt = scheduledAt;
        return o;
    }

    public void markSent(Instant at) { this.status = PushOutboxStatus.SENT; this.sentAt = at; }
    public void markSkipped()        { this.status = PushOutboxStatus.SKIPPED; }
}
