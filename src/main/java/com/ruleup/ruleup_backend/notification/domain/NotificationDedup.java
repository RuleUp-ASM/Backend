package com.ruleup.ruleup_backend.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 중복 제어 ({@code notification_dedup}). 기본 24시간, 티어 경계 알림만 1주.
 *
 * <p><b>{@code targetKey} 는 없으면 빈 문자열</b>이다. NULL 을 쓰면 MySQL 유니크가 동작하지 않아
 * 같은 알림이 여러 행으로 들어가고 중복 제어가 통째로 무력화된다.
 */
@Entity
@Table(name = "notification_dedup")
@IdClass(NotificationDedup.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDedup {

    /** targetKey 가 없을 때 쓰는 값. NULL 대신 이것을 쓴다. */
    public static final String NO_TARGET = "";

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private String type;

    @Id
    @Column(name = "target_key", nullable = false, length = 100, updatable = false)
    private String targetKey;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;

    public static NotificationDedup of(UUID userId, String type, String targetKey, Instant at) {
        NotificationDedup d = new NotificationDedup();
        d.userId = userId;
        d.type = type;
        d.targetKey = normalize(targetKey);
        d.lastSentAt = at;
        return d;
    }

    public static String normalize(String targetKey) {
        return (targetKey == null || targetKey.isBlank()) ? NO_TARGET : targetKey;
    }

    public void touch(Instant at) {
        this.lastSentAt = at;
    }

    public record Key(UUID userId, String type, String targetKey) implements Serializable {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId)
                    && Objects.equals(type, k.type) && Objects.equals(targetKey, k.targetKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, type, targetKey);
        }
    }
}
