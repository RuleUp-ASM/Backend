package com.ruleup.ruleup_backend.notification.domain;

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

/** 사용자 알림 (Notification 테이블). 닉네임/사진 변경 요청 등. */
@Entity
@Table(name = "Notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "readAt")
    private Instant readAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static Notification create(UUID userId, NotificationType type, String title, String message) {
        Notification n = new Notification();
        n.id = UuidGenerator.generate();
        n.userId = userId;
        n.type = type;
        n.title = title;
        n.message = message;
        return n;
    }

    public void markRead() {
        if (this.readAt == null) this.readAt = Instant.now();
    }

    public boolean isRead() { return readAt != null; }
}