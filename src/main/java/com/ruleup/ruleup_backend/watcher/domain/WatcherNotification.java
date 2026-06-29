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
import java.time.LocalDate;
import java.util.UUID;

/**
 * 감시자 실패 통지 큐 1건(§9). 적재(PENDING) → 스윕이 발송(SENT)하거나, 발송 시점에 비활성이면 SKIPPED.
 * 야간 디퍼는 scheduledAt 으로 표현(22~08시 도달분은 08:00로 미룸).
 */
@Entity
@Table(name = "WatcherNotification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatcherNotification extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "watcherId", nullable = false, updatable = false)
    private UUID watcherId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "failedUserId", nullable = false, updatable = false)
    private UUID failedUserId;

    @Column(name = "targetDate", nullable = false, updatable = false)
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    private WatcherChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WatcherNotificationStatus status;

    @Column(name = "scheduledAt", nullable = false)
    private Instant scheduledAt;

    @Column(name = "sentAt")
    private Instant sentAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static WatcherNotification enqueue(UUID watcherId, UUID challengeId, UUID failedUserId,
                                              LocalDate targetDate, WatcherChannel channel, Instant scheduledAt) {
        WatcherNotification n = new WatcherNotification();
        n.id = UuidGenerator.generate();
        n.watcherId = watcherId;
        n.challengeId = challengeId;
        n.failedUserId = failedUserId;
        n.targetDate = targetDate;
        n.channel = channel;
        n.status = WatcherNotificationStatus.PENDING;
        n.scheduledAt = scheduledAt;
        return n;
    }

    public void markSent(Instant at)    { this.status = WatcherNotificationStatus.SENT; this.sentAt = at; }
    public void markSkipped()           { this.status = WatcherNotificationStatus.SKIPPED; }
}
