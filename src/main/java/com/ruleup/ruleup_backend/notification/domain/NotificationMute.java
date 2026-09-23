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
 * 챌린지별 음소거 ({@code notification_mutes}). 유형별 토글과 <b>AND</b> 로 결합한다 —
 * 둘 중 하나만 꺼져 있어도 푸시를 보내지 않는다.
 */
@Entity
@Table(name = "notification_mutes")
@IdClass(NotificationMute.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationMute {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Column(name = "muted_at", nullable = false)
    private Instant mutedAt;

    public static NotificationMute of(UUID userId, UUID challengeId, Instant at) {
        NotificationMute m = new NotificationMute();
        m.userId = userId;
        m.challengeId = challengeId;
        m.mutedAt = at;
        return m;
    }

    public record Key(UUID userId, UUID challengeId) implements Serializable {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(userId, k.userId) && Objects.equals(challengeId, k.challengeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, challengeId);
        }
    }
}
