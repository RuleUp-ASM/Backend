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
 * 기능(B) 유형별 토글 ({@code notification_settings}).
 *
 * <p><b>행이 없으면 기본 ON</b> 으로 해석한다. 그래서 신규 타입을 추가할 때 전원 백필이 필요 없다 —
 * 페이지2에서 공지·댓글 5종이 합류해도 마이그레이션이 따라붙지 않는다.
 *
 * <p>OFF 는 <b>푸시만 생략</b>한다. 알림함 적재는 그대로다.
 */
@Entity
@Table(name = "notification_settings")
@IdClass(NotificationSetting.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSetting {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** 필수(A) 타입은 이 테이블에 들어올 수 없다 — 서비스가 거부한다. */
    @Id
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private String type;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static NotificationSetting of(UUID userId, NotificationType type, boolean enabled, Instant at) {
        NotificationSetting s = new NotificationSetting();
        s.userId = userId;
        s.type = type.name();
        s.enabled = enabled;
        s.updatedAt = at;
        return s;
    }

    public void apply(boolean enabled, Instant at) {
        this.enabled = enabled;
        this.updatedAt = at;
    }

    public record Key(UUID userId, String type) implements Serializable {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId) && Objects.equals(type, k.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, type);
        }
    }
}
